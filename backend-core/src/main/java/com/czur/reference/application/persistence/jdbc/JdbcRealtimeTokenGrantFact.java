package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.RenewRealtimeTokenUnitOfWork;
import com.czur.reference.protocol.json.RealtimeTokenGrantJson;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

/**
 * V014 单行续期事实的包内严格读取原语。
 *
 * <p>实时握手核验、同键续期重放和提交未知回查必须共用这一处规范 JSON、
 * 原文 SHA、Token SHA、会议/流和时间校验，避免两个入口对同一坏事实做出相反
 * 判定。本类不查询数据库、不持有连接或事务，也不生成新 Token。</p>
 */
final class JdbcRealtimeTokenGrantFact {
    private JdbcRealtimeTokenGrantFact() {
    }

    /**
     * 严格读取当前行；调用方的 SQL 必须提供幂等键及 Grant 正文、两类摘要、
     * 签发/过期时间，且已用 WHERE 限定会议。
     * 任何坏字段抛固定消息，绝不把 Grant JSON、Token 或幂等键放进异常。
     */
    static StoredGrant read(ResultSet rows, String meetingId, String expectedKey)
            throws SQLException {
        try {
            String key = rows.getString("idempotency_key");
            String json = rows.getString("grant_json");
            byte[] jsonSha = rows.getBytes("grant_sha256");
            byte[] tokenSha = rows.getBytes("token_sha256");
            Timestamp issued = rows.getTimestamp("issued_at");
            Timestamp expires = rows.getTimestamp("expires_at");
            require(key != null && key.length() >= 16 && key.length() <= 128
                    && key.codePoints().noneMatch(Character::isISOControl)
                    && (expectedKey == null || expectedKey.equals(key)));
            require(json != null && jsonSha != null && jsonSha.length == 32
                    && tokenSha != null && tokenSha.length == 32
                    && issued != null && expires != null
                    && issued.toInstant().isBefore(expires.toInstant()));
            RenewRealtimeTokenUnitOfWork.Grant grant = RealtimeTokenGrantJson.decode(json);
            require(meetingId.equals(grant.meetingId())
                    && meetingId.equals(grant.streamId())
                    && expires.toInstant().equals(grant.expiresAt())
                    && MessageDigest.isEqual(jsonSha,
                            sha256(json.getBytes(StandardCharsets.UTF_8)))
                    && MessageDigest.isEqual(tokenSha,
                            sha256(grant.sessionToken().getBytes(StandardCharsets.UTF_8))));
            return new StoredGrant(key, json, tokenSha, issued.toInstant(),
                    expires.toInstant());
        } catch (CorruptFact failure) {
            throw failure;
        } catch (RuntimeException malformed) {
            throw new CorruptFact();
        }
    }

    static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    private static void require(boolean valid) {
        if (!valid) throw new CorruptFact();
    }

    /** 只输出是否存在，不隐式记录敏感正文或摘要。 */
    record StoredGrant(String key, String grantJson, byte[] tokenSha256,
            Instant issuedAt, Instant expiresAt) {
        StoredGrant {
            tokenSha256 = tokenSha256.clone();
        }

        @Override public byte[] tokenSha256() { return tokenSha256.clone(); }

        boolean matchesTokenDigest(byte[] requestedDigest) {
            return MessageDigest.isEqual(requestedDigest, tokenSha256);
        }

        @Override public String toString() {
            return "StoredGrant[persisted=true]";
        }
    }

    /** 固定诊断消息，不包含数据库字段正文。 */
    static final class CorruptFact extends IllegalStateException {
        private CorruptFact() {
            super("Realtime token grant fact is inconsistent");
        }
    }
}
