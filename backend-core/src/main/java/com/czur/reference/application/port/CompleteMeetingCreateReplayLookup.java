package com.czur.reference.application.port;

import com.czur.reference.application.command.CreateMeetingResponseFact;
import com.czur.reference.domain.meeting.MeetingOwnership;

import java.util.Objects;

/**
 * 完整 Create 的 V013 首次响应只读查询端口。
 *
 * <p>新认证创建流程须在身份与权限确认后、权益预占和实时会话分配前调用本端口。
 * 部署和主体来自可信服务端上下文；实现负责生成 {@code meeting.create.v2} 不透明
 * 范围，调用方不得传入数据库 scope。它只查询已提交事实，不申请权益、不生成令牌，
 * 也不对查询未命中作出“别的创建者不会提交”的保证。首次未命中后的并发裁决仍由
 * {@link CompleteMeetingCreationUnitOfWork} 完成。</p>
 *
 * <p>请求侧已有事实必须先严格校验并比较摘要；异摘要不允许读取或解码原响应。
 * 同摘要才严格读取 V013 原响应。任何事实损坏或存储故障都关闭式返回固定状态，
 * 不能当成未命中而继续预占权益。</p>
 */
public interface CompleteMeetingCreateReplayLookup {
    /**
     * 按可信部署、主体、外部幂等键与完整 Create 请求摘要查询首次终态。
     *
     * @param request 已认证调用方提供的身份范围与完整请求摘要；不含原始凭据
     * @return 五种固定结果之一；只有 {@link Replayed} 携带原成功响应事实
     */
    Result lookup(Request request);

    /** 查询身份；摘要必须来自完整 Create 请求快照的规范编码。 */
    record Request(String deploymentScope, String subjectScope,
                   String idempotencyKey, String requestDigest) {
        public Request {
            requireText("deploymentScope", deploymentScope);
            MeetingOwnership.requireOwnedSubjectScope(subjectScope);
            requireText("idempotencyKey", idempotencyKey);
            if (requestDigest == null || !requestDigest.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("requestDigest must be a lowercase SHA-256 hex");
            }
        }

        @Override
        public String toString() {
            return "CompleteMeetingCreateReplayLookup.Request[redacted]";
        }
    }

    /** 调用方必须穷举处理的只读裁决。 */
    enum Status { NOT_FOUND, REPLAYED, CONFLICT, INCONSISTENT, UNAVAILABLE }

    /** 稳定结果联合类型；所有分支的默认诊断输出均不得含业务标识或令牌。 */
    sealed interface Result permits NotFound, Replayed, Conflict, Inconsistent, Unavailable {
        Status status();
    }

    /** 当前已提交快照中不存在该 scope/key；不保证并发首次创建不会稍后提交。 */
    record NotFound() implements Result {
        @Override public Status status() { return Status.NOT_FOUND; }
    }

    /** 同摘要时从 V013 严格读回的原始首次成功响应。 */
    record Replayed(CreateMeetingResponseFact response) implements Result {
        public Replayed { Objects.requireNonNull(response, "response"); }
        @Override public Status status() { return Status.REPLAYED; }
        @Override public String toString() {
            return "CompleteMeetingCreateReplayLookup.Replayed[response=redacted]";
        }
    }

    /** 同 scope/key 已有另一规范请求；实现不能为该分支解码响应。 */
    record Conflict() implements Result {
        @Override public Status status() { return Status.CONFLICT; }
    }

    /** 已保存请求或响应事实损坏、消失，不能安全重放。 */
    record Inconsistent() implements Result {
        @Override public Status status() { return Status.INCONSISTENT; }
    }

    /** 数据库或连接生命周期失败，不能把失败解释为未命中。 */
    record Unavailable() implements Result {
        @Override public Status status() { return Status.UNAVAILABLE; }
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
