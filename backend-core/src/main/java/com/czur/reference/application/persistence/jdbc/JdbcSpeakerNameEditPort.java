package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.artifact.MeetingBodyArtifactBudget;
import com.czur.reference.application.command.MeetingCommandContext;
import com.czur.reference.application.port.MeetingManifest;
import com.czur.reference.application.port.artifact.ArtifactStore;
import com.czur.reference.application.port.speaker.SpeakerIdentityEnrollmentPort;
import com.czur.reference.application.port.speaker.SpeakerNameEditPort;
import com.czur.reference.application.port.speaker.SpeakerResultAccess;
import com.czur.reference.protocol.json.MeetingManifestJson;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 人工名称保存到 transcript.final 的可选 speakerDisplayName，不重写原文、编号或时间。
 * 复用唯一结果行和不可变 Artifact；无需人物库或新表。声纹网络调用发生在提交、释放连接后。
 * 旧原文没有该字段时仍按 speaker.identity 显示；人工编辑时冻结本场名称，SINGLE 登记也不会
 * 因身份产物更新而连带改掉其他段。显式 null 表示保留该段的未命名编号显示。
 */
public final class JdbcSpeakerNameEditPort implements SpeakerNameEditPort {
    private static final Logger LOGGER = Logger.getLogger(JdbcSpeakerNameEditPort.class.getName());
    private final JdbcDatabaseContext database;
    private final JdbcClientResultArtifacts artifacts;
    private final SpeakerIdentityEnrollmentPort enrollment;

    public JdbcSpeakerNameEditPort(JdbcDatabaseContext database, ArtifactStore store,
            SpeakerIdentityEnrollmentPort enrollment) {
        this(database, store, enrollment, MeetingBodyArtifactBudget.LEGACY_MAX_BYTES);
    }

    /** 名称编辑会重发同一正文产物，必须沿用部署的发布／读取预算。 */
    public JdbcSpeakerNameEditPort(JdbcDatabaseContext database, ArtifactStore store,
            SpeakerIdentityEnrollmentPort enrollment, long maxClientArtifactBytes) {
        this.database = database;
        this.artifacts = new JdbcClientResultArtifacts(store, database.engine(), maxClientArtifactBytes);
        this.enrollment = enrollment;
    }

    @Override public Result edit(MeetingCommandContext context, String meetingId,
            String sessionSpeakerId, Edit edit) {
        return editAuthorized(SpeakerResultAccess.owner(context), meetingId, sessionSpeakerId, edit);
    }

    @Override public Result editAuthorized(SpeakerResultAccess access, String meetingId,
            String sessionSpeakerId, Edit edit) {
        int revision;
        int changed;
        try (var connection = database.dataSource().getConnection()) {
            connection.setAutoCommit(false);
            // 与既有登记／结果更新共用会议行锁，避免两个操作覆盖彼此的 Manifest。
            try (var statement = connection.prepareStatement("SELECT deployment_scope, subject_scope, state FROM "
                    + table(JdbcDatabaseTable.MEETING) + " WHERE meeting_id=? FOR UPDATE")) {
                JdbcValues.setText(statement, 1, database.engine(), meetingId);
                try (var rows = statement.executeQuery()) {
                    if (!rows.next()) return new Rejected(Reason.NOT_FOUND);
                    if (!access.allows(meetingId, JdbcValues.text(rows, "deployment_scope"),
                            JdbcValues.text(rows, "subject_scope")))
                        return new Rejected(Reason.ACCESS_DENIED);
                    if (!"READY".equals(JdbcValues.text(rows, "state"))) return new Rejected(Reason.INVALID_STATE);
                }
            }
            MeetingManifest manifest;
            try (var statement = connection.prepareStatement("SELECT manifest_json FROM "
                    + table(JdbcDatabaseTable.MEETING_CLIENT_RESULT) + " WHERE meeting_id=?")) {
                JdbcValues.setText(statement, 1, database.engine(), meetingId);
                try (var rows = statement.executeQuery()) {
                    if (!rows.next()) return new Rejected(Reason.NOT_FOUND);
                    manifest = MeetingManifestJson.decode(rows.getString("manifest_json"));
                }
            }
            var transcript = manifest.artifacts().stream().filter(ref -> "transcript.final".equals(ref.type()))
                    .findFirst().orElse(null);
            if (transcript == null) return new Rejected(Reason.SEGMENT_NOT_FOUND);
            ObjectNode body = artifacts.read(connection, transcript);
            String currentName = null;
            var identity = manifest.artifacts().stream().filter(ref -> "speaker.identity".equals(ref.type()))
                    .findFirst().orElse(null);
            if (identity != null) {
                for (var person : artifacts.read(connection, identity).path("speakers"))
                    if (sessionSpeakerId.equals(person.path("sessionSpeakerId").asText())
                            && person.path("displayName").isTextual()) currentName = person.path("displayName").asText();
            }
            String before = body.toString();
            changed = applyNames(body, sessionSpeakerId, currentName, edit);
            if (changed == 0) return new Rejected(Reason.SEGMENT_NOT_FOUND);
            revision = manifest.manifestRevision();
            if (!before.equals(body.toString())) {
                int artifactRevision = transcript.revision() + 1;
                String id = "art_client_transcript_" + JdbcClientResultArtifacts.digest(
                        (meetingId + "\0" + artifactRevision + "\0" + body).getBytes(StandardCharsets.UTF_8));
                var replacement = artifacts.publishReady(connection, id, meetingId, transcript.type(),
                        transcript.language(), artifactRevision, body);
                var refs = new ArrayList<>(manifest.artifacts());
                refs.set(refs.indexOf(transcript), replacement);
                var updated = new MeetingManifest(manifest.specVersion(), meetingId, ++revision,
                        manifest.profile(), manifest.title(), manifest.status(), manifest.startedAt(), manifest.endedAt(),
                        manifest.durationMs(), manifest.languages(), refs, manifest.warnings());
                try (var statement = connection.prepareStatement("UPDATE " + table(JdbcDatabaseTable.MEETING_CLIENT_RESULT)
                        + " SET manifest_json=? WHERE meeting_id=?")) {
                    statement.setString(1, MeetingManifestJson.encode(updated));
                    JdbcValues.setText(statement, 2, database.engine(), meetingId);
                    statement.executeUpdate();
                }
            }
            connection.commit();
        } catch (Exception failure) {
            LOGGER.log(Level.WARNING, "本场发言人名称保存失败 reason={0}", failure.getClass().getSimpleName());
            return new Rejected(Reason.UNAVAILABLE);
        }
        if (!edit.remember()) return new Updated(revision, changed, Registration.NOT_REQUESTED);
        if (enrollment == null) return new Updated(revision, changed, Registration.UNAVAILABLE);
        try {
            var result = enrollment.enrollAuthorized(access, meetingId, sessionSpeakerId, edit.displayName());
            if (result instanceof SpeakerIdentityEnrollmentPort.Enrolled enrolled)
                return new Updated(enrolled.manifestRevision(), changed, Registration.REGISTERED);
            var reason = ((SpeakerIdentityEnrollmentPort.Rejected) result).reason();
            return new Updated(revision, changed, switch (reason) {
                case SAMPLE_UNAVAILABLE -> Registration.SAMPLE_UNAVAILABLE;
                case NOT_GRANTED -> Registration.NOT_GRANTED;
                default -> Registration.UNAVAILABLE;
            });
        } catch (RuntimeException failure) {
            return new Updated(revision, changed, Registration.UNAVAILABLE);
        }
    }

    /** 同步名称投影，单元测试和实际写入共用；返回用户选中的段落数。 */
    static int applyNames(ObjectNode transcript, String speakerId, String previousName, Edit edit) {
        int matched = 0;
        for (var segment : transcript.path("segments")) {
            if (!speakerId.equals(segment.path("sessionSpeakerId").asText())) continue;
            var item = (ObjectNode) segment;
            if (edit.scope() == Scope.ALL || edit.segmentId().equals(item.path("segmentId").asText())) {
                item.put("speakerDisplayName", edit.displayName());
                matched++;
            } else if (!item.has("speakerDisplayName")) {
                item.put("speakerDisplayName", previousName);
            }
        }
        return matched;
    }
    private String table(JdbcDatabaseTable table) { return table.physicalName(database.engine()); }
}
