package com.czur.reference.application.port;

import com.czur.reference.application.port.model.MeetingAudioFrame;
import com.czur.reference.domain.meeting.MeetingIdPolicy;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * P2 单会议单逻辑流的持久音频输入端口。
 *
 * <p>调用方须先通过既有实时凭据核验，并在第一次输入前完成 v2 Start；本端口不
 * 验证 Token，不承载网络连接或 Model Driver。实现仍须在每次写事务中核对最新
 * 会议/输入状态、当前连接 ID 和 writerEpoch，不能依赖握手时的旧状态。
 * {@code streamId = meetingId}；连接 ID 只在服务端生成和传递，不进入公开协议。</p>
 *
 * <p>每条物理 WSS 连接恰好接受一次 {@code stream_start}，并只调用一次
 * {@link #bindWriter(String, WriterConnectionId)}。后续 16-06 网络层须串行化
 * 同连接的开始请求；重复 {@code stream_start} 按协议错误拒绝并关闭连接，
 * 绝不再次绑定，也不返回缓存的旧 {@link Bound}。绑定提交结果未知时必须关闭
 * 该连接，新连接用新的服务端 ID 绑定，再按持久游标补发未获
 * ACK 的帧。数据库只保存当前 writer ID 和 epoch，不保留旧 ID 历史，也不支持
 * 旧连接再次绑定。帧或声明提交未知时只读回查，查无仍为未知。</p>
 *
 * <p>只有已确认提交的 {@link Stored} 与 {@link Bound} 结果是持久事实；
 * {@link Unknown} 不得用于发送 {@code durable=true} ACK。ACK 的位置来自
 * 最高连续持久帧游标，
 * 与后续模型处理游标和会议事件序号分别维护。完成声明只是待核验事实，不签发
 * {@code finalizationReceipt}。端口实现的生命周期由数据库运行时持有；日志
 * 只记录稳定故障分类，不逐帧记录音频、Token、连接 ID、摘要或完整正文。</p>
 */
public interface DurableAudioInputPort {
    /**
     * 每条物理 WSS 连接恰好调用一次，原子递增持久 epoch 并栅栏旧写者。
     * 重复 stream_start 由网络层按协议错误拒绝并关闭，不再次调用本方法；
     * 提交未知时关闭连接并创建新 ID，不用旧 connectionId 重绑。
     */
    BindResult bindWriter(String meetingId, WriterConnectionId connectionId);

    /**
     * 按连续序号追加完整帧；同序同内容幂等，异内容冲突，跳号拒绝。
     * 持久端按 V013 首次赢家的音频 Profile、帧上限和会议时长校验。
     */
    WriteResult append(WriterBinding binding, MeetingAudioFrame frame);

    /**
     * 只读回查最高连续持久游标及完成声明，用于帧提交未知和恢复裁决。
     * 需要判断某帧是否已提交时，再用 replay 读取并比对完整帧内容摘要。
     * 不执行重绑或模型处理。
     */
    CursorResult readCursor(String meetingId);

    /**
     * 按序号升序有界重放已持久帧。实现必须在读取时复核字节长度和完整内容
     * 摘要，并保证从 {@code afterSequence + 1} 到本页末无缺口；已接管游标
     * 覆盖的帧若缺失，返回坏事实，不得伪装成空页或无效请求游标。
     */
    ReplayResult replay(String meetingId, long afterSequence, int limit);

    /**
     * 以当前 epoch 幂等保存完整完成声明；允许声明先于全部帧，但后续帧不得
     * 越过声明终点。相同声明重试返回已存事实，异声明冲突。
     */
    WriteResult complete(WriterBinding binding, Completion declaration);

    /** 服务端内部连接标识；每条新连接只生成一个并只绑定一次。 */
    final class WriterConnectionId {
        private final UUID value;

        private WriterConnectionId(UUID value) {
            this.value = value;
        }

        public static WriterConnectionId newConnection() {
            return new WriterConnectionId(UUID.randomUUID());
        }

        /** 仅持久适配器使用；不得写入协议、普通日志或客户端状态。 */
        public UUID value() {
            return value;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof WriterConnectionId id && value.equals(id.value);
        }

        @Override
        public int hashCode() {
            return value.hashCode();
        }

        @Override
        public String toString() {
            return "WriterConnectionId[redacted]";
        }
    }

    /** 成功绑定后交给帧与声明写事务的内部栅栏。 */
    record WriterBinding(String meetingId, WriterConnectionId connectionId, long writerEpoch) {
        public WriterBinding {
            MeetingIdPolicy.requireValid(meetingId);
            Objects.requireNonNull(connectionId, "connectionId");
            if (writerEpoch <= 0) throw new IllegalArgumentException("writerEpoch must be positive");
        }

        @Override
        public String toString() {
            return "WriterBinding[redacted]";
        }
    }

    /** 连续持久接管游标；零表示尚无任何可 ACK 帧。 */
    record Cursor(long acceptedStreamSequence, long acceptedAudioPositionMs,
                  Completion completion) {
        public Cursor {
            if (acceptedStreamSequence < 0 || acceptedAudioPositionMs < 0
                    || (acceptedStreamSequence == 0 && acceptedAudioPositionMs != 0)) {
                throw new IllegalArgumentException("invalid accepted cursor");
            }
        }

        @Override
        public String toString() {
            return "Cursor[acceptedStreamSequence=" + acceptedStreamSequence
                    + ", completionPresent=" + (completion != null) + ']';
        }
    }

    /** 完整 input_complete 字段；录音摘要仅指独立录音原文件。 */
    record Completion(long lastStreamSequence, long lastAudioPositionMs,
                      String recordingChecksum, long recordingSizeBytes, String requestId) {
        public Completion {
            if (lastStreamSequence <= 0 || lastAudioPositionMs < 0
                    || recordingSizeBytes < 0) {
                throw new IllegalArgumentException("invalid completion position or size");
            }
            Objects.requireNonNull(recordingChecksum, "recordingChecksum");
            if (recordingChecksum.isEmpty() != (recordingSizeBytes == 0)
                    || (!recordingChecksum.isEmpty()
                    && !recordingChecksum.matches("[0-9a-f]{64}"))) {
                throw new IllegalArgumentException("invalid recording checksum and size");
            }
            if (requestId == null || requestId.isBlank() || requestId.length() > 128
                    || requestId.indexOf('\r') >= 0 || requestId.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("invalid requestId");
            }
        }

        @Override
        public String toString() {
            return "Completion[redacted]";
        }
    }

    sealed interface BindResult permits Bound, Rejected, Unavailable, Unknown { }

    /** 已确认持久绑定；网络层不得对重复 stream_start 返回此旧结果。 */
    record Bound(WriterBinding binding, Cursor cursor) implements BindResult {
        public Bound {
            Objects.requireNonNull(binding, "binding");
            Objects.requireNonNull(cursor, "cursor");
        }
    }

    sealed interface WriteResult permits Stored, Rejected, Unavailable, Unknown { }

    /** 已确认持久帧或声明；帧追加可从非零游标生成 durable ACK。 */
    record Stored(Cursor cursor, boolean replayed) implements WriteResult {
        public Stored { Objects.requireNonNull(cursor, "cursor"); }
    }

    /** 拒绝原因不暴露会议是否存在、帧字节或凭据。 */
    enum Rejection { FENCED, DENIED, CONFLICT, GAP, LIMIT_EXCEEDED, INVALID_INPUT, FACT_INCONSISTENT }

    record Rejected(Rejection reason) implements BindResult, WriteResult {
        public Rejected { Objects.requireNonNull(reason, "reason"); }
    }

    /** 数据库不可用；没有可发送的 ACK。 */
    record Unavailable(boolean retryable) implements BindResult, WriteResult { }

    /** 事务提交确认丢失；绑定时关闭连接，帧/声明写入时只读回查。 */
    record Unknown() implements BindResult, WriteResult { }

    sealed interface CursorResult permits CursorFound, Missing, FactInconsistentRead,
            UnavailableRead { }

    /** 已存在的持久流游标；不暴露当前 writer ID 或 epoch 给读取者。 */
    record CursorFound(Cursor cursor) implements CursorResult {
        public CursorFound { Objects.requireNonNull(cursor, "cursor"); }
    }
    record Missing() implements CursorResult, ReplayResult { }
    /** 持久流或帧事实缺损；提交未知回查不得解释成“尚未提交”。 */
    record FactInconsistentRead() implements CursorResult, ReplayResult { }
    record UnavailableRead() implements CursorResult, ReplayResult { }

    sealed interface ReplayResult permits FramePage, Missing, FactInconsistentRead,
            UnavailableRead, InvalidCursor { }

    /** 从请求下一序号起连续，严格升序且不超过请求 limit；音频数组由帧复制。 */
    record FramePage(List<MeetingAudioFrame> frames, long acceptedStreamSequence)
            implements ReplayResult {
        public FramePage {
            frames = List.copyOf(frames);
            if (acceptedStreamSequence < 0) throw new IllegalArgumentException("negative cursor");
        }

        @Override
        public String toString() {
            return "FramePage[count=" + frames.size() + "]";
        }
    }

    record InvalidCursor() implements ReplayResult { }
}
