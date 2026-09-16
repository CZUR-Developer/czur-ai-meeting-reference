package com.czur.reference.application.command;

import com.czur.reference.application.error.IdempotencyConflictException;
import com.czur.reference.application.error.MeetingAlreadyExistsException;
import com.czur.reference.application.error.MeetingNotFoundException;
import com.czur.reference.application.port.IdempotencyStore;
import com.czur.reference.application.port.MeetingRepository;
import com.czur.reference.domain.meeting.MeetingAggregate;
import com.czur.reference.domain.meeting.MeetingEvent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 会议应用层命令边界。
 *
 * <p>该服务把传输层请求转换为领域命令，并负责幂等记录、事实源读写
 * 和生命周期日志。模型、存储供应商、认证和计费均不在此类中实现；它们
 * 应通过后续插件端口接入。方法使用实例锁保证参考内存实现中的“检查并
 * 写入”原子性，生产数据库实现仍需使用事务和唯一约束。</p>
 */
public final class MeetingCommandService {
    private static final String CREATE_SCOPE = "meeting.create";
    private static final String STOP_SCOPE = "meeting.stop";
    private static final Logger LOGGER = Logger.getLogger(MeetingCommandService.class.getName());

    private final MeetingRepository meetings;
    private final IdempotencyStore idempotency;

    public MeetingCommandService(MeetingRepository meetings, IdempotencyStore idempotency) {
        this.meetings = Objects.requireNonNull(meetings, "meetings");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
    }

    /**
     * 创建会议并返回稳定快照。
     *
     * <p>同一范围内重复使用相同幂等键且请求摘要一致时，直接返回首次
     * 结果；摘要不一致时抛出 {@link IdempotencyConflictException}。</p>
     */
    public synchronized MeetingCommandResult create(CreateMeetingCommand command) {
        Objects.requireNonNull(command, "command");
        String digest = digest(command.meetingId(), command.deploymentScope(), command.clientSessionId(), command.profileId(),
                Integer.toString(command.profileRevision()), command.requestedAt().toString());
        MeetingAggregate aggregate = new MeetingAggregate(
                command.meetingId(), command.deploymentScope(), command.clientSessionId(), command.profileId(),
                command.profileRevision(), command.requestedAt());
        MeetingCommandResult result = new MeetingCommandResult(
                MeetingCommandResult.Operation.CREATED, aggregate.snapshot());
        IdempotencyStore.IdempotencyRecord candidate = new IdempotencyStore.IdempotencyRecord(
                CREATE_SCOPE, command.idempotencyKey(), digest, result);
        IdempotencyStore.Reservation reservation = idempotency.reserve(candidate);
        if (reservation.status() == IdempotencyStore.ReservationStatus.CONFLICT) {
            LOGGER.log(Level.WARNING, "幂等键请求摘要冲突 scope={0}", CREATE_SCOPE);
            throw new IdempotencyConflictException(CREATE_SCOPE, command.idempotencyKey());
        }
        if (reservation.status() == IdempotencyStore.ReservationStatus.REPLAY) {
            LOGGER.log(Level.INFO, "会议创建请求幂等命中 meetingId={0}", command.meetingId());
            return reservation.record().result();
        }
        if (meetings.find(command.meetingId()).isPresent()) {
            idempotency.release(candidate);
            throw new MeetingAlreadyExistsException(command.meetingId());
        }
        var sameSession = meetings.findByDeploymentScopeAndClientSessionId(
                command.deploymentScope(), command.clientSessionId());
        if (sameSession.isPresent()) {
            idempotency.release(candidate);
            LOGGER.log(Level.WARNING,
                    "同一部署范围和客户端会话已存在会议 deploymentScope={0} existingMeetingId={1}",
                    new Object[]{command.deploymentScope(), sameSession.get().meetingId()});
            throw new MeetingAlreadyExistsException(sameSession.get().meetingId());
        }
        try {
            meetings.save(result.meeting());
        } catch (RuntimeException failure) {
            idempotency.release(candidate);
            LOGGER.log(Level.SEVERE, "会议创建持久化失败，已释放幂等预占 meetingId={0}", command.meetingId());
            throw failure;
        }
        LOGGER.log(Level.INFO, "会议已创建 meetingId={0}", command.meetingId());
        return result;
    }

    /**
     * 记录停止意图。最终输入接管和产物收口由后续异步 Worker 完成。
     */
    public synchronized MeetingCommandResult stop(StopMeetingCommand command) {
        Objects.requireNonNull(command, "command");
        String digest = digest(command.meetingId(), command.requestedAt().toString());
        // 先读已有记录，保持旧接口在会议快照已被清理时仍可重放历史结果；
        // 最终写入仍必须经过 reserve，避免两个新请求并发穿透检查。
        var previous = idempotency.find(STOP_SCOPE, command.idempotencyKey());
        if (previous.isPresent()) {
            if (!MessageDigest.isEqual(
                    digest.getBytes(StandardCharsets.UTF_8),
                    previous.get().requestDigest().getBytes(StandardCharsets.UTF_8))) {
                LOGGER.log(Level.WARNING, "幂等键请求摘要冲突 scope={0}", STOP_SCOPE);
                throw new IdempotencyConflictException(STOP_SCOPE, command.idempotencyKey());
            }
            LOGGER.log(Level.INFO, "会议停止请求幂等命中 meetingId={0}", command.meetingId());
            return previous.get().result();
        }
        MeetingAggregate.MeetingSnapshot stored = meetings.find(command.meetingId())
                .orElseThrow(() -> new MeetingNotFoundException(command.meetingId()));
        MeetingAggregate aggregate = MeetingAggregate.fromSnapshot(stored);
        aggregate.apply(MeetingEvent.STOP_REQUESTED, command.requestedAt());
        MeetingCommandResult result = new MeetingCommandResult(
                MeetingCommandResult.Operation.STOP_ACCEPTED, aggregate.snapshot());
        IdempotencyStore.IdempotencyRecord candidate = new IdempotencyStore.IdempotencyRecord(
                STOP_SCOPE, command.idempotencyKey(), digest, result);
        IdempotencyStore.Reservation reservation = idempotency.reserve(candidate);
        if (reservation.status() == IdempotencyStore.ReservationStatus.CONFLICT) {
            LOGGER.log(Level.WARNING, "幂等键请求摘要冲突 scope={0}", STOP_SCOPE);
            throw new IdempotencyConflictException(STOP_SCOPE, command.idempotencyKey());
        }
        if (reservation.status() == IdempotencyStore.ReservationStatus.REPLAY) {
            LOGGER.log(Level.INFO, "会议停止请求幂等命中 meetingId={0}", command.meetingId());
            return reservation.record().result();
        }
        try {
            meetings.save(result.meeting());
        } catch (RuntimeException failure) {
            idempotency.release(candidate);
            LOGGER.log(Level.SEVERE, "会议停止持久化失败，已释放幂等预占 meetingId={0}", command.meetingId());
            throw failure;
        }
        LOGGER.log(Level.INFO, "会议停止意图已接受 meetingId={0}", command.meetingId());
        return result;
    }

    private static String digest(String... values) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                sha256.update(value.getBytes(StandardCharsets.UTF_8));
                sha256.update((byte) 0);
            }
            return HexFormat.of().formatHex(sha256.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
