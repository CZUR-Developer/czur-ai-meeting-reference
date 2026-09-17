package com.czur.reference.application.command;

import com.czur.reference.application.error.IdempotencyConflictException;
import com.czur.reference.application.error.MeetingAlreadyExistsException;
import com.czur.reference.application.error.MeetingNotFoundException;
import com.czur.reference.application.port.IdempotencyStore;
import com.czur.reference.application.port.MeetingRepository;
import com.czur.reference.application.port.MeetingUnitOfWork;
import com.czur.reference.application.port.OutboxStore;
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
 * 写入”顺序；JDBC 调用方应使用 {@link MeetingUnitOfWork} 构造入口，把会议
 * 快照、幂等记录和 Outbox 追加放入同一数据库事务。</p>
 */
public final class MeetingCommandService {
    private static final String CREATE_SCOPE = "meeting.create";
    private static final String STOP_SCOPE = "meeting.stop";
    private static final Logger LOGGER = Logger.getLogger(MeetingCommandService.class.getName());

    private final MeetingRepository meetings;
    private final IdempotencyStore idempotency;
    private final MeetingUnitOfWork unitOfWork;

    public MeetingCommandService(MeetingRepository meetings, IdempotencyStore idempotency) {
        this.meetings = Objects.requireNonNull(meetings, "meetings");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
        this.unitOfWork = null;
    }

    /**
     * 使用统一工作单元执行会议命令。
     *
     * <p>该入口供 JDBC/生产适配器使用，创建或停止会议时会把会议快照、
     * 幂等记录和对应 Outbox 事件放在同一事务中。旧的双端口构造器仍保留，
     * 用于内存参考实现和已有调用方；两种入口不能同时配置。</p>
     */
    public MeetingCommandService(MeetingUnitOfWork unitOfWork) {
        this.meetings = null;
        this.idempotency = null;
        this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork");
    }

    /**
     * 创建会议并返回稳定快照。
     *
     * <p>同一范围内重复使用相同幂等键且请求摘要一致时，直接返回首次
     * 结果；摘要不一致时抛出 {@link IdempotencyConflictException}。</p>
     */
    public synchronized MeetingCommandResult create(CreateMeetingCommand command) {
        Objects.requireNonNull(command, "command");
        if (unitOfWork != null) {
            return unitOfWork.execute(transaction -> createInTransaction(
                    command, transaction.meetings(), transaction.idempotency(), transaction.outbox()));
        }
        return createInTransaction(command, meetings, idempotency, null);
    }

    private MeetingCommandResult createInTransaction(
            CreateMeetingCommand command,
            MeetingRepository meetingRepository,
            IdempotencyStore idempotencyStore,
            OutboxStore outboxStore) {
        String digest = digest(command.meetingId(), command.deploymentScope(), command.clientSessionId(), command.profileId(),
                Integer.toString(command.profileRevision()), command.requestedAt().toString());
        MeetingAggregate aggregate = MeetingAggregate.legacyUnowned(
                command.meetingId(), command.deploymentScope(), command.clientSessionId(), command.profileId(),
                command.profileRevision(), command.requestedAt());
        MeetingCommandResult result = new MeetingCommandResult(
                MeetingCommandResult.Operation.CREATED, aggregate.snapshot());
        IdempotencyStore.IdempotencyRecord candidate = new IdempotencyStore.IdempotencyRecord(
                CREATE_SCOPE, command.idempotencyKey(), digest, result);
        IdempotencyStore.Reservation reservation = idempotencyStore.reserve(candidate);
        if (reservation.status() == IdempotencyStore.ReservationStatus.CONFLICT) {
            LOGGER.log(Level.WARNING, "幂等键请求摘要冲突 scope={0}", CREATE_SCOPE);
            throw new IdempotencyConflictException(CREATE_SCOPE, command.idempotencyKey());
        }
        if (reservation.status() == IdempotencyStore.ReservationStatus.REPLAY) {
            LOGGER.log(Level.INFO, "会议创建请求幂等命中 meetingId={0}", command.meetingId());
            return reservation.record().result();
        }
        if (meetingRepository.find(command.meetingId()).isPresent()) {
            idempotencyStore.release(candidate);
            throw new MeetingAlreadyExistsException(command.meetingId());
        }
        var sameSession = meetingRepository.findByDeploymentScopeAndClientSessionId(
                command.deploymentScope(), command.clientSessionId());
        if (sameSession.isPresent()) {
            idempotencyStore.release(candidate);
            LOGGER.log(Level.WARNING,
                    "同一部署范围和客户端会话已存在会议 deploymentScope={0} existingMeetingId={1}",
                    new Object[]{command.deploymentScope(), sameSession.get().meetingId()});
            throw new MeetingAlreadyExistsException(sameSession.get().meetingId());
        }
        try {
            meetingRepository.save(result.meeting());
            if (outboxStore != null) {
                appendCommandEvent(outboxStore, result, "MeetingCreated", command.requestedAt());
            }
        } catch (RuntimeException failure) {
            idempotencyStore.release(candidate);
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
        if (unitOfWork != null) {
            return unitOfWork.execute(transaction -> stopInTransaction(
                    command, transaction.meetings(), transaction.idempotency(), transaction.outbox()));
        }
        return stopInTransaction(command, meetings, idempotency, null);
    }

    private MeetingCommandResult stopInTransaction(
            StopMeetingCommand command,
            MeetingRepository meetingRepository,
            IdempotencyStore idempotencyStore,
            OutboxStore outboxStore) {
        String digest = digest(command.meetingId(), command.requestedAt().toString());
        // 先读已有记录，保持旧接口在会议快照已被清理时仍可重放历史结果；
        // 最终写入仍必须经过 reserve，避免两个新请求并发穿透检查。
        var previous = idempotencyStore.find(STOP_SCOPE, command.idempotencyKey());
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
        MeetingAggregate.MeetingSnapshot stored = meetingRepository.find(command.meetingId())
                .orElseThrow(() -> new MeetingNotFoundException(command.meetingId()));
        boolean firstStopRequest = stored.state() != com.czur.reference.domain.meeting.MeetingState.STOPPING
                && stored.state() != com.czur.reference.domain.meeting.MeetingState.FINALIZING;
        MeetingAggregate aggregate = MeetingAggregate.fromSnapshot(stored);
        if (firstStopRequest) {
            aggregate.apply(MeetingEvent.STOP_REQUESTED, command.requestedAt());
        }
        MeetingCommandResult result = new MeetingCommandResult(
                MeetingCommandResult.Operation.STOP_ACCEPTED, aggregate.snapshot());
        IdempotencyStore.IdempotencyRecord candidate = new IdempotencyStore.IdempotencyRecord(
                STOP_SCOPE, command.idempotencyKey(), digest, result);
        IdempotencyStore.Reservation reservation = idempotencyStore.reserve(candidate);
        if (reservation.status() == IdempotencyStore.ReservationStatus.CONFLICT) {
            LOGGER.log(Level.WARNING, "幂等键请求摘要冲突 scope={0}", STOP_SCOPE);
            throw new IdempotencyConflictException(STOP_SCOPE, command.idempotencyKey());
        }
        if (reservation.status() == IdempotencyStore.ReservationStatus.REPLAY) {
            LOGGER.log(Level.INFO, "会议停止请求幂等命中 meetingId={0}", command.meetingId());
            return reservation.record().result();
        }
        try {
            if (firstStopRequest) {
                meetingRepository.save(result.meeting());
                if (outboxStore != null) {
                    appendCommandEvent(outboxStore, result, "MeetingStopRequested", command.requestedAt());
                }
            }
        } catch (RuntimeException failure) {
            idempotencyStore.release(candidate);
            LOGGER.log(Level.SEVERE, "会议停止持久化失败，已释放幂等预占 meetingId={0}", command.meetingId());
            throw failure;
        }
        LOGGER.log(Level.INFO, "会议停止意图已接受 meetingId={0}", command.meetingId());
        return result;
    }

    /**
     * 追加命令对应的稳定 Outbox 事件。调用方仅在工作单元入口调用本方法；
     * 旧双端口入口没有 Outbox，并在进入本方法前显式跳过事件追加。
     */
    private static void appendCommandEvent(
            OutboxStore outboxStore,
            MeetingCommandResult result,
            String eventType,
            Instant occurredAt) {
        Objects.requireNonNull(outboxStore, "outboxStore");
        MeetingAggregate.MeetingSnapshot meeting = result.meeting();
        String eventId = "meeting-" + digest(eventType, meeting.meetingId());
        String payload = "{\"meetingId\":" + quote(meeting.meetingId())
                + ",\"deploymentScope\":" + quote(meeting.deploymentScope())
                + ",\"state\":" + quote(meeting.state().name())
                + ",\"updatedAt\":" + quote(meeting.updatedAt().toString()) + "}";
        OutboxStore.AppendResult append = outboxStore.append(OutboxStore.OutboxEvent.pending(
                eventId, "Meeting", meeting.meetingId(), eventType, payload,
                occurredAt, occurredAt));
        if (append.status() == OutboxStore.AppendStatus.CONFLICT) {
            throw new IllegalStateException("命令 Outbox 事件内容冲突 eventType=" + eventType);
        }
    }

    private static String quote(String value) {
        StringBuilder result = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (c < 0x20) {
                        result.append(String.format("\\u%04x", (int) c));
                    } else {
                        result.append(c);
                    }
                }
            }
        }
        return result.append('"').toString();
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
