package com.czur.reference.application.port;

import com.czur.reference.domain.meeting.MeetingIdPolicy;

import java.util.Objects;

/**
 * 只读判断一场会议的 V015 流式音频是否具备最终接管的完整材料。
 *
 * <p>实现须从一个一致的持久读取视图核对完成声明、连续的 1..last 帧、每帧
 * 原始字节与帧摘要、最后帧终点与声明终点，并按版本化规则形成完整流摘要。
 * 只有全部检查通过才返回 {@link Ready}；声明先到、末帧未到或尚无声明时返回
 * {@link Pending}。持久事实损坏独立返回 {@link Inconsistent}，不能伪装成等待。
 * P2 Mock Profile 不要求独立录音文件；若声明带录音信息但上传/校验未实现，
 * 不能返回 Ready。</p>
 *
 * <p>端口不核对 Stop 或会议归属，不决定源/目标语言、接管时间、用量或 receipt，
 * 不写入数据库，也不调用模型。会议级 Reconciler 另行核对 Stop 与 V013 冻结
 * 事实，再将 Ready 数据交给既有 Admission。异常/日志不得包含音频、摘要或
 * 凭据；可由不同线程并发调用，实现自行保证读取隔离。</p>
 */
public interface CompletedAudioReadinessPort {
    /**
     * 查询已持久音频材料的收口就绪状态。
     *
     * @param meetingId 合法会议 ID；实现不得从客户端游标推断完成状态
     * @return 稳定分类；存储短暂不可用时返回 Unavailable 而非 Pending
     */
    Result check(String meetingId);

    sealed interface Result permits Ready, Pending, Missing, Inconsistent, Unavailable { }

    /** 完整音频事实，不包含接管时刻或任何模型输出。 */
    record Ready(String meetingId, long lastAcceptedSequence,
                 long audioDurationMs, String contentDigest) implements Result {
        public Ready {
            MeetingIdPolicy.requireValid(meetingId);
            if (lastAcceptedSequence <= 0 || audioDurationMs <= 0) {
                throw new IllegalArgumentException("invalid complete audio position");
            }
            Objects.requireNonNull(contentDigest, "contentDigest");
            if (!contentDigest.matches("sha256-v1:[0-9a-f]{64}")) {
                throw new IllegalArgumentException("invalid complete audio digest");
            }
        }

        @Override
        public String toString() {
            return "CompletedAudioReadinessPort.Ready[redacted]";
        }
    }

    /** 流已存在，但尚无经核验的完整音频；Stop 后仍可继续补帧。 */
    record Pending() implements Result { }
    /** 不存在该会议的 V015 流，不能据此推断会议本身不存在。 */
    record Missing() implements Result { }
    /** 存在互相矛盾或损坏的持久音频事实，不能自动当作 Pending 重试。 */
    record Inconsistent() implements Result { }
    /** 存储暂时不可读；调用方可稍后重试。 */
    record Unavailable() implements Result { }
}
