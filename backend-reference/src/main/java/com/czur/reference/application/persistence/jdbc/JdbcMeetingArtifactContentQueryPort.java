package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.finalization.MockFinalizationArtifactContent;
import com.czur.reference.application.port.MeetingManifest;
import com.czur.reference.application.port.MeetingManifestPort;
import com.czur.reference.application.port.artifact.ArtifactStore;
import javax.sql.DataSource;
import java.util.Objects;

/** 参考产物读取的兼容入口；仅在参考模块注册旧Mock canonical格式。 */
public final class JdbcMeetingArtifactContentQueryPort extends JdbcArtifactContentQueryPort {
    public JdbcMeetingArtifactContentQueryPort(DataSource source,
            MeetingManifestPort manifest, ArtifactStore store) {
        super(source, JdbcDatabaseEngine.MYSQL, manifest, store,
                JdbcMeetingArtifactContentQueryPort::matchesLegacy);
    }
    public JdbcMeetingArtifactContentQueryPort(JdbcDatabaseContext database,
            MeetingManifestPort manifest, ArtifactStore store) {
        super(database, manifest, store, JdbcMeetingArtifactContentQueryPort::matchesLegacy);
    }
    /** 沿用旧Mock形状与语种校验；核心读取器仅通过接口调用，不认识Mock类。 */
    public static boolean matchesLegacy(byte[] bytes, MeetingManifest.ArtifactRef reference) {
        var decoded = MockFinalizationArtifactContent.decodeCanonical(bytes);
        return "transcript.final".equals(decoded.artifactType())
                && Objects.equals(reference.language(), decoded.language());
    }
}
