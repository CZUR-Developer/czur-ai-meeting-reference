package com.czur.reference.application.command;

import com.czur.reference.application.port.CreateRealtimeSessionAllocator;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 参考后端的进程内首次实时会话身份分配器。
 *
 * <p>同一服务实例内用 {@code ConcurrentHashMap.compute} 原子分配 256 位随机不透明 Token
 * 和首次过期时间。WSS 公网地址基准、TTL、时钟及随机源由服务端装配提供；
 * 每场连接地址固定拼成 {@code /v1/realtime/{meetingId}}，客户端不能指定路径。
 * 分配结果必须通过 Create 事务写入 V013；正式 WSS 入口在连接时按数据库赢家
 * 验证授权与会话，绝不从本缓存核验 Token。进程重启后的首次响应由 V013 重放，
 * 不依赖本缓存；仅创建此分配器也不代表 WSS listener 已启动。</p>
 *
 * <p>若候选创建一直未持久化而令牌已过期，下次分配会产生新的首次候选；已
 * 持久化的请求应由 V013 在分配前重放，不通过本类续期。每 64 次调用惰性扫描
 * 并移除过期缓存项，且只移除仍为同一旧值的映射，避免长期运行按历史候选无限
 * 累积。仍需在部署侧限制同时有效的会话数及 Token TTL。</p>
 */
public final class ReferenceCreateRealtimeSessionAllocator
        implements CreateRealtimeSessionAllocator {
    private static final Logger LOGGER = Logger.getLogger(
            ReferenceCreateRealtimeSessionAllocator.class.getName());
    private static final Base64.Encoder TOKEN_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final int TOKEN_BYTES = 32;
    private static final long REAP_EVERY_CALLS = 64;

    private final URI publicOrigin;
    private final Duration tokenTtl;
    private final Clock clock;
    private final SecureRandom random;
    private final ConcurrentHashMap<Request, CreateMeetingResponseFact.RealtimeSession> sessions =
            new ConcurrentHashMap<>();
    private final AtomicLong calls = new AtomicLong();

    /**
     * @param publicOrigin 服务端配置的 WSS 公网源地址，例如 {@code wss://ai.example.com}；
     *                     不接受额外路径、查询、用户信息或片段
     * @param tokenTtl 正数的首次令牌存活时间，由可信部署配置决定
     * @param clock 服务端时钟；每次调用读取以判定候选是否已过期
     * @param random 安全随机源，不得使用固定种子作生产装配
     */
    public ReferenceCreateRealtimeSessionAllocator(
            URI publicOrigin, Duration tokenTtl, Clock clock, SecureRandom random) {
        Objects.requireNonNull(publicOrigin, "publicOrigin");
        String path = publicOrigin.getRawPath();
        if (!"wss".equalsIgnoreCase(publicOrigin.getScheme())
                || publicOrigin.getHost() == null || publicOrigin.getHost().isBlank()
                || publicOrigin.getUserInfo() != null || publicOrigin.getFragment() != null
                || publicOrigin.getQuery() != null
                || (path != null && !path.isEmpty() && !"/".equals(path))) {
            throw new IllegalArgumentException("publicOrigin must be a WSS origin URL");
        }
        this.publicOrigin = publicOrigin;
        this.tokenTtl = Objects.requireNonNull(tokenTtl, "tokenTtl");
        if (tokenTtl.isZero() || tokenTtl.isNegative()) {
            throw new IllegalArgumentException("tokenTtl must be positive");
        }
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
    }

    @Override
    public CreateMeetingResponseFact.RealtimeSession allocate(Request request) {
        Objects.requireNonNull(request, "request");
        if (calls.incrementAndGet() % REAP_EVERY_CALLS == 0) {
            Instant reapTime = Objects.requireNonNull(clock.instant(), "clock.instant()");
            sessions.forEach((key, value) -> {
                if (!reapTime.isBefore(value.expiresAt())) {
                    sessions.remove(key, value);
                }
            });
        }
        return sessions.compute(request, (ignored, previous) -> {
            // 在该身份的原子区内读取时间，避免排队等待时越过旧令牌到期边界。
            Instant now = Objects.requireNonNull(clock.instant(), "clock.instant()");
            if (previous != null && now.isBefore(previous.expiresAt())) {
                return previous;
            }
            Instant expiresAt;
            try {
                expiresAt = now.plus(tokenTtl);
            } catch (DateTimeException | ArithmeticException failure) {
                throw new IllegalStateException("realtime token expiry is unavailable");
            }
            byte[] tokenBytes = new byte[TOKEN_BYTES];
            random.nextBytes(tokenBytes);
            URI meetingEndpoint = URI.create("wss://" + publicOrigin.getRawAuthority()
                    + "/v1/realtime/" + request.meetingId());
            var session = new CreateMeetingResponseFact.RealtimeSession(
                    meetingEndpoint, CreateMeetingResponseFact.REALTIME_SUBPROTOCOL,
                    TOKEN_ENCODER.encodeToString(tokenBytes), expiresAt);
            LOGGER.log(Level.FINE, "参考 Create 实时会话身份已分配");
            return session;
        });
    }
}
