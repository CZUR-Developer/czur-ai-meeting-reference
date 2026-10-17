package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.MeetingManifest;
import com.czur.reference.application.port.artifact.ArtifactReadyMetadataStore;
import com.czur.reference.application.port.artifact.ArtifactStore;
import com.czur.reference.application.port.artifact.ArtifactStoreException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HexFormat;

/** 复用客户端小型 JSON 产物的不可变发布与 READY 登记，不另建结果状态或存储。 */
final class JdbcClientResultArtifacts {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ArtifactStore store;
    private final JdbcDatabaseEngine engine;

    JdbcClientResultArtifacts(ArtifactStore store, JdbcDatabaseEngine engine) {
        this.store = store;
        this.engine = engine;
    }

    MeetingManifest.ArtifactRef publishReady(Connection connection, String artifactId,
            String meetingId, String type, String language, int revision, ObjectNode body)
            throws ArtifactStoreException, SQLException {
        byte[] content = body.toString().getBytes(StandardCharsets.UTF_8);
        String checksum = "sha256:" + digest(content);
        var spec = new ArtifactStore.WriteIntentSpec(artifactId, meetingId, type,
                "application/json", language, revision, content.length, checksum);
        var prepared = store.createWriteIntent(spec);
        if (prepared.status() == ArtifactStore.PrepareStatus.CONFLICT) {
            throw new IllegalStateException("client result artifact intent conflict");
        }
        store.writeContent(prepared.intent(), new ByteArrayInputStream(content));
        var descriptor = store.completeWrite(prepared.intent(),
                new ArtifactStore.ContentClaim(content.length, checksum)).artifact();
        var persisted = new JdbcArtifactReadyMetadataStore(connection, engine).persistReady(descriptor);
        if (persisted.status() == ArtifactReadyMetadataStore.PersistStatus.CONFLICT) {
            throw new IllegalStateException("client result artifact metadata conflict");
        }
        return new MeetingManifest.ArtifactRef(artifactId, type, "application/json", language,
                revision, content.length, checksum, MeetingManifest.ArtifactStatus.READY,
                persisted.metadata().recordedAt(), null, null);
    }

    ObjectNode read(Connection connection, MeetingManifest.ArtifactRef ref)
            throws SQLException, ArtifactStoreException, IOException {
        var metadata = new JdbcArtifactReadyMetadataStore(connection, engine)
                .findByArtifactId(ref.artifactId()).orElseThrow();
        try (var read = store.openRead(metadata.artifact().intent().binding())) {
            return (ObjectNode) JSON.readTree(read.content());
        }
    }

    static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
