package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.artifact.MeetingBodyArtifactBudget;
import com.czur.reference.application.command.MeetingCommandContext;
import com.czur.reference.application.port.MeetingManifest;
import com.czur.reference.application.port.artifact.ArtifactStore;
import com.czur.reference.application.port.speaker.SpeakerIdentityEnrollmentPort;
import com.czur.reference.application.port.speaker.SpeakerIdentityProvider;
import com.czur.reference.application.port.speaker.SpeakerResultAccess;
import com.czur.reference.protocol.json.MeetingManifestJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 复用原客户端结果行进行发言人命名注册；不引入第二套 Manifest revision 表。
 *
 * <p>读取 READY 归属与同场音频后关闭连接，再调用 Provider。返回后仅在短事务
 * 内锁定原会议，发布新版身份 JSON 并更新客户端 Manifest。原文、录音、时间和
 * genTime 不变；旧产物保留不可变性质。该对象不拥有数据库／Store／Provider。</p>
 */
public final class JdbcSpeakerIdentityEnrollmentPort implements SpeakerIdentityEnrollmentPort {
    private static final Logger LOGGER = Logger.getLogger(JdbcSpeakerIdentityEnrollmentPort.class.getName());
    private final JdbcDatabaseContext database;
    private final SpeakerIdentityProvider provider;
    private final JdbcClientResultArtifacts artifacts;

    public JdbcSpeakerIdentityEnrollmentPort(JdbcDatabaseContext database, ArtifactStore store,
            SpeakerIdentityProvider provider) {
        this(database, store, provider, MeetingBodyArtifactBudget.LEGACY_MAX_BYTES);
    }

    /** 身份产物沿用与正文相同的发布预算，避免第二条发布路径绕过上限。 */
    public JdbcSpeakerIdentityEnrollmentPort(JdbcDatabaseContext database, ArtifactStore store,
            SpeakerIdentityProvider provider, long maxClientArtifactBytes) {
        this.database = Objects.requireNonNull(database, "database");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.artifacts = new JdbcClientResultArtifacts(Objects.requireNonNull(store, "store"),
                database.engine(), maxClientArtifactBytes);
    }

    @Override
    public Result enroll(MeetingCommandContext context, String meetingId,
            String sessionSpeakerId, String displayName) {
        return enrollAuthorized(SpeakerResultAccess.owner(context), meetingId, sessionSpeakerId, displayName);
    }

    @Override public Result enrollAuthorized(SpeakerResultAccess access, String meetingId,
            String sessionSpeakerId, String displayName) {
        try {
            SpeakerIdentityProvider.MeetingSpeakers input;
            SpeakerIdentityProvider.Person existing;
            try (Connection connection = database.dataSource().getConnection()) {
                connection.setReadOnly(true);
                connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                connection.setAutoCommit(false);
                var reason = checkMeeting(connection, access, meetingId, false);
                if (reason != null) return new Rejected(reason);
                input = JdbcClientMeetingResultFinalizer.speakerInput(connection, database.engine(), meetingId);
                if (input == null) return new Rejected(Reason.NOT_GRANTED);
                if (input.speakers().stream().noneMatch(item ->
                        item.sessionSpeakerId().equals(sessionSpeakerId))) {
                    return new Rejected(Reason.SAMPLE_UNAVAILABLE);
                }
                var manifest = readManifest(connection, meetingId);
                var ref = identityRef(manifest);
                if (ref == null) return new Rejected(Reason.SAMPLE_UNAVAILABLE);
                JsonNode speaker = findSpeaker(artifacts.read(connection, ref), sessionSpeakerId);
                existing = speaker != null && speaker.path("personId").isTextual()
                        ? new SpeakerIdentityProvider.Person(speaker.path("personId").asText(),
                                speaker.path("displayName").asText()) : null;
                if (existing != null && existing.displayName().equals(displayName)) {
                    return new Enrolled(existing, manifest.manifestRevision());
                }
                connection.rollback();
            }
            // 供应商请求、音频读取及切片不占用会议连接或行锁。
            var person = Objects.requireNonNull(provider.enroll(new SpeakerIdentityProvider.Enrollment(
                    input, sessionSpeakerId, displayName, existing)), "enrolled person");
            try (Connection connection = database.dataSource().getConnection()) {
                connection.setAutoCommit(false);
                try {
                    var reason = checkMeeting(connection, access, meetingId, true);
                    if (reason != null) {
                        connection.rollback();
                        return new Rejected(reason);
                    }
                    MeetingManifest current = readManifest(connection, meetingId);
                    var oldRef = identityRef(current);
                    ObjectNode body = artifacts.read(connection, oldRef);
                    ObjectNode speaker = findSpeaker(body, sessionSpeakerId);
                    if (speaker == null) {
                        connection.rollback();
                        return new Rejected(Reason.SAMPLE_UNAVAILABLE);
                    }
                    speaker.put("personId", person.personId());
                    speaker.put("displayName", person.displayName());
                    int revision = oldRef.revision() + 1;
                    String id = "art_client_speaker_" + JdbcClientResultArtifacts.digest(
                            meetingId.getBytes(StandardCharsets.UTF_8)) + "_" + revision;
                    var ref = artifacts.publishReady(connection, id, meetingId,
                            "speaker.identity", null, revision, body);
                    var refs = new ArrayList<>(current.artifacts());
                    refs.removeIf(item -> "speaker.identity".equals(item.type()));
                    refs.add(ref);
                    var updated = new MeetingManifest(current.specVersion(), current.meetingId(),
                            current.manifestRevision() + 1, current.profile(), current.title(),
                            current.status(), current.startedAt(), current.endedAt(), current.durationMs(),
                            current.languages(), refs, current.warnings());
                    try (var statement = connection.prepareStatement("UPDATE "
                            + table(JdbcDatabaseTable.MEETING_CLIENT_RESULT)
                            + " SET manifest_json=? WHERE meeting_id=?")) {
                        statement.setString(1, MeetingManifestJson.encode(updated));
                        JdbcValues.setText(statement, 2, database.engine(), meetingId);
                        if (statement.executeUpdate() != 1) throw new SQLException("client result missing");
                    }
                    connection.commit();
                    LOGGER.log(Level.INFO, "会后人物注册完成 manifestRevision={0}", updated.manifestRevision());
                    return new Enrolled(person, updated.manifestRevision());
                } catch (Exception failure) {
                    connection.rollback();
                    throw failure;
                }
            }
        } catch (SpeakerIdentityProvider.SampleUnavailable unavailable) {
            return new Rejected(Reason.SAMPLE_UNAVAILABLE);
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            LOGGER.log(Level.WARNING, "会后人物注册暂未完成 reason={0}", failure.getClass().getSimpleName());
            return new Rejected(Reason.UNAVAILABLE);
        }
    }

    private Reason checkMeeting(Connection connection, SpeakerResultAccess access,
            String meetingId, boolean lock) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT deployment_scope, subject_scope, state FROM "
                + table(JdbcDatabaseTable.MEETING) + " WHERE meeting_id=?" + (lock ? " FOR UPDATE" : ""))) {
            JdbcValues.setText(statement, 1, database.engine(), meetingId);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) return Reason.NOT_FOUND;
                if (!access.allows(meetingId, JdbcValues.text(rows, "deployment_scope"),
                        JdbcValues.text(rows, "subject_scope"))) {
                    return Reason.ACCESS_DENIED;
                }
                return "READY".equals(JdbcValues.text(rows, "state")) ? null : Reason.INVALID_STATE;
            }
        }
    }

    private MeetingManifest readManifest(Connection connection, String meetingId) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT manifest_json FROM "
                + table(JdbcDatabaseTable.MEETING_CLIENT_RESULT) + " WHERE meeting_id=?")) {
            JdbcValues.setText(statement, 1, database.engine(), meetingId);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("client result missing");
                return MeetingManifestJson.decode(rows.getString("manifest_json"));
            }
        }
    }

    private static MeetingManifest.ArtifactRef identityRef(MeetingManifest manifest) {
        return manifest.artifacts().stream().filter(ref -> "speaker.identity".equals(ref.type()))
                .findFirst().orElse(null);
    }

    private static ObjectNode findSpeaker(ObjectNode body, String sessionSpeakerId) {
        for (JsonNode item : body.path("speakers")) {
            if (sessionSpeakerId.equals(item.path("sessionSpeakerId").asText())) return (ObjectNode) item;
        }
        return null;
    }

    private String table(JdbcDatabaseTable table) { return table.physicalName(database.engine()); }
}
