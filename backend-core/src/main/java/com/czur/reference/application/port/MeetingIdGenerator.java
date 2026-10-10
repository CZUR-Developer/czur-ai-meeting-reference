package com.czur.reference.application.port;

/**
 * 为新会议生成不透明标识的应用端口。
 *
 * <p>生产适配器必须使用密码学安全随机源或等价的不可预测 ID 服务。实现不得从
 * 部署范围、主体、幂等键、规范请求摘要、客户端会话或当前时间派生 meetingId，
 * 也不得把这些业务字段编码到标识中。这样日志、URL 或跨系统引用暴露 meetingId
 * 时，不会同时泄漏归属和请求特征。</p>
 *
 * <p>该端口只负责生成候选值，不负责保证最终唯一性。持久化适配器必须继续依赖
 * 数据库唯一约束处理极低概率碰撞，并返回稳定冲突结果，不能覆盖已有会议。</p>
 */
@FunctionalInterface
public interface MeetingIdGenerator {
    /** 生成一个与当前业务上下文无关的新不透明会议标识。 */
    String newMeetingId();
}
