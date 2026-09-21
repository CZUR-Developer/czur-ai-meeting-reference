package com.czur.reference.application.port;

import java.util.Objects;
import java.util.Optional;

/**
 * 完整 Manifest 的追加式权威事实端口。
 *
 * <p>该端口只冻结应用层需要的版本并发语义，不规定 JSON 表示、内容摘要算法、
 * JDBC 表结构、事务实现或 Worker 编排。实现必须按 {@code meetingId +
 * manifestRevision} 保存不可变事实，不能原地覆盖已经发布的 revision。</p>
 *
 * <p>相同 meetingId、revision 和完整 Manifest 正文的重复追加返回
 * {@link AppendStatus#REPLAYED}；相同 meetingId、revision 但正文不同返回
 * {@link AppendStatus#CONTENT_CONFLICT}；当前最新 revision 与调用方声明的前置
 * revision 不一致返回 {@link AppendStatus#VERSION_CONFLICT}。实现比较正文时必须
 * 忽略 Manifest 之外的查询参数，例如结果链接的 {@code genTime}。</p>
 *
 * <p>实现按以下顺序裁决，保证超时后的相同请求仍可稳定重放：先检查请求中的
 * meetingId 与 revision 是否已有事实；已有同正文返回 REPLAYED，已有异文返回
 * CONTENT_CONFLICT。仅当该 revision 尚不存在时，再比较当前 latest 与
 * expectedPreviousRevision，不一致返回 VERSION_CONFLICT，一致才追加。</p>
 */
public interface ManifestFactStore {
    /**
     * 返回会议当前最新的完整 Manifest；尚无任何 revision 时返回空。
     *
     * <p>实现必须把输入视为公开会议标识并关闭式校验，且不得用空 Manifest
     * 表示“正在生成”。</p>
     */
    Optional<MeetingManifest> findLatest(String meetingId);

    /**
     * 按请求声明的前置 revision 原子追加一个不可变 Manifest revision。
     *
     * <p>方法返回后，调用方只根据稳定状态判断追加、重放或冲突；存储异常和
     * 事务未知等基础设施结果由后续实现使用既有应用错误边界表达，不能伪装成
     * 下列业务冲突。</p>
     */
    AppendResult append(AppendRequest request);

    /**
     * 一次追加请求。
     *
     * <p>{@link ExpectedPreviousRevision.None} 只允许创建 revision 1；
     * {@link ExpectedPreviousRevision.Present} 必须为正数，并且新 Manifest 的
     * revision 必须恰好等于前置 revision 加一。构造时先完成这些静态校验，避免
     * 无效请求进入数据库或 Worker。</p>
     */
    record AppendRequest(
            MeetingManifest manifest,
            ExpectedPreviousRevision expectedPreviousRevision) {
        public AppendRequest {
            Objects.requireNonNull(manifest, "manifest");
            Objects.requireNonNull(expectedPreviousRevision, "expectedPreviousRevision");
            int revision = manifest.manifestRevision();
            if (expectedPreviousRevision instanceof ExpectedPreviousRevision.None) {
                if (revision != 1) {
                    throw new IllegalArgumentException(
                            "first manifest revision must be 1");
                }
            } else {
                int previous = ((ExpectedPreviousRevision.Present)
                        expectedPreviousRevision).revision();
                if (previous == Integer.MAX_VALUE || revision != previous + 1) {
                    throw new IllegalArgumentException(
                            "manifest revision must immediately follow expected revision");
                }
            }
        }

        /** 诊断只保留 revision，不输出会议标识、标题、产物或告警正文。 */
        @Override
        public String toString() {
            return "ManifestFactStore.AppendRequest[manifestRevision="
                    + manifest.manifestRevision()
                    + ", expectedPreviousRevision=" + expectedPreviousRevision + ']';
        }
    }

    /** 调用方对当前最新 revision 的显式预期。 */
    sealed interface ExpectedPreviousRevision
            permits ExpectedPreviousRevision.None, ExpectedPreviousRevision.Present {
        /** 声明目标会议还没有任何 Manifest revision。 */
        record None() implements ExpectedPreviousRevision {
            @Override
            public String toString() {
                return "ExpectedPreviousRevision.None";
            }
        }

        /** 声明追加前应存在的最新 Manifest revision。 */
        record Present(int revision) implements ExpectedPreviousRevision {
            public Present {
                if (revision < 1) {
                    throw new IllegalArgumentException(
                            "expected previous revision must be positive");
                }
            }

            @Override
            public String toString() {
                return "ExpectedPreviousRevision.Present[revision=" + revision + ']';
            }
        }
    }

    /** 一次原子追加的稳定业务结果。 */
    record AppendResult(AppendStatus status) {
        public AppendResult {
            Objects.requireNonNull(status, "status");
        }
    }

    /**
     * 追加结果；冲突状态不会改变任何已持久化 Manifest revision。
     */
    enum AppendStatus {
        /** 新 revision 已经持久化。 */
        APPENDED,
        /** 相同 meetingId、revision 和正文已经存在，本次为幂等重放。 */
        REPLAYED,
        /** 相同 meetingId 和 revision 已存在，但正文不同。 */
        CONTENT_CONFLICT,
        /** 当前最新 revision 与请求声明的前置 revision 不一致。 */
        VERSION_CONFLICT
    }
}
