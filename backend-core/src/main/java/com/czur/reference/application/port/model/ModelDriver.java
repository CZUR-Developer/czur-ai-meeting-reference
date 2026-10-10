package com.czur.reference.application.port.model;

import com.czur.reference.domain.capability.CapabilityDescriptor;

import java.time.Instant;

/**
 * 模型与会议编排之间唯一的实时 Driver 端口。
 *
 * <p>该端口只处理公共会话、音频帧和规范化事件。实现可以在内部使用 HTTP、
 * WebSocket、SDK 或 Sidecar，但不得依赖 Gateway、MeetingRepository、
 * ArtifactStore、EntitlementProvider 或设备连接。替换模型时应只新增/替换
 * Driver、配置、注册项和 Fixture，不能修改会议状态机与公开协议。</p>
 *
 * <p>生命周期为 {@code openSession → start → acceptAudio* → completeInput /
 * abort → close}。除 {@code openSession} 外每个方法都要求同一 Driver 返回的
 * {@link ModelDriverSession}。所有调用必须检查 deadline 和取消信号；失败
 * 返回稳定的 {@link ModelDriverError}，不得把供应商异常直接抛出。</p>
 */
public interface ModelDriver {

    /**
     * 返回构建期注册且已通过契约测试的能力事实。
     *
     * @return 不可变 Capability Descriptor；不能根据单场会议动态夸大能力
     */
    CapabilityDescriptor describe();

    /**
     * 在不产生真实计费副作用的前提下验证静态配置。
     * 每个 Driver 必须显式实现，不能通过默认成功绕过 context、配置和 Ready
     * 检查；Mock 也要覆盖正常、取消和超时行为。
     *
     * @param context 本次启动校验的 deadline 和取消上下文
     * @return 成功或已经规范化的配置错误
     */
    ModelDriverResult<Void> validateSettings(ModelOperationContext context);

    /**
     * 打开一个逻辑 Provider 会话。
     *
     * <p>同一幂等键、相同 spec 和同一个 sink 实例必须返回同一逻辑句柄；
     * spec 或 sink 任一不同都返回 CONFLICT，避免恢复连接静默绑定旧回调。
     * 跨连接恢复需要后续独立的 reattach/replay 契约，不能借用 openSession
     * 幂等重放。成功返回的句柄处于 READY，尚未开始接收音频。</p>
     *
     * @param spec 会议、公共音频格式、能力和部署配置版本
     * @param sink 接收规范化字幕和终态的回调
     * @param idempotencyKey 打开模型会话的稳定幂等键
     * @param context 本次调用的 deadline 和取消上下文
     * @return READY 会话句柄，或稳定的失败结果
     */
    ModelDriverResult<ModelDriverSession> openSession(
            RealtimeSessionSpec spec,
            ModelEventSink sink,
            String idempotencyKey,
            ModelOperationContext context);

    /**
     * 从 READY 开始接收音频；重复 start 应幂等成功。
     *
     * @param session openSession 返回的同一 Driver 会话句柄
     * @param context 本次调用的 deadline 和取消上下文
     * @return 启动成功或稳定错误
     */
    ModelDriverResult<Void> start(ModelDriverSession session, ModelOperationContext context);

    /**
     * 接收一个已经按公共序号排列的音频帧。
     *
     * <p>Driver 应返回 DUPLICATE、OUT_OF_ORDER 或 BACKPRESSURED 等确定状态，
     * 不得静默丢帧。成功接收后才能在回调中产生 partial 字幕事件。</p>
     *
     * @param session openSession 返回的同一 Driver 会话句柄
     * @param frame 已按公共格式规范化的音频帧；Driver 不得修改其内容
     * @param context 本次调用的 deadline 和取消上下文
     * @return 接收游标/背压结果，或稳定错误
     */
    ModelDriverResult<AudioAcceptance> acceptAudio(
            ModelDriverSession session,
            MeetingAudioFrame frame,
            ModelOperationContext context);

    /**
     * 通知系统音频切源、丢帧或 Broker 重启。
     *
     * <p>返回值必须明确 CONTINUED、PROVIDER_REOPEN_REQUIRED 或 FAILED；本期
     * Mock 只支持 CONTINUED。通知不重置公共 streamSequence。</p>
     *
     * @param session 当前正在流式输入的会话
     * @param discontinuity 已持久记录的系统音频缺口
     * @param context 本次调用的 deadline 和取消上下文
     * @return 继续、重开 Provider 或失败动作
     */
    ModelDriverResult<DiscontinuityAction> notifyDiscontinuity(
            ModelDriverSession session,
            AudioDiscontinuity discontinuity,
            ModelOperationContext context);

    /**
     * 停止接收新帧并排空 Provider final 事件。
     *
     * <p>lastSequence 必须等于 Driver 已接受的最高序号；重复调用使用同一
     * 幂等键返回原结果。返回的 InputCompletion 仅代表模型输入排空，不代表
     * MeetingRepository 已完成 INPUT_ACCEPTED。final 或 terminal 回调失败时
     * 不能返回成功；terminal 失败允许用同一幂等键重试，且不得重复 final。</p>
     *
     * @param session 当前流式模型会话
     * @param lastSequence App 声明的最后一个音频序号
     * @param idempotencyKey 输入完成操作的稳定幂等键
     * @param context 本次调用的 deadline 和取消上下文
     * @return 模型排空游标和 final 数量，或稳定错误
     */
    ModelDriverResult<InputCompletion> completeInput(
            ModelDriverSession session,
            long lastSequence,
            String idempotencyKey,
            ModelOperationContext context);

    /**
     * 取消尚未完成的模型会话；实现必须幂等并释放模型侧输入缓冲。
     * 取消后的会话进入 FAILED（终止原因通过稳定错误码 CANCELLED 表示）。
     * 若取消 terminal 回调失败，本次调用返回 CALLBACK_FAILED；同一幂等键
     * 可以只重试 terminal 交付，不重复执行模型侧取消。
     *
     * @param session 要取消的模型会话
     * @param reason 脱敏后的稳定取消原因码，不得放入用户文本
     * @param idempotencyKey 取消操作的稳定幂等键
     * @param context 本次调用的 deadline 和取消上下文
     * @return 取消成功或稳定错误
     */
    ModelDriverResult<Void> abort(
            ModelDriverSession session,
            String reason,
            String idempotencyKey,
            ModelOperationContext context);

    /**
     * 取消的别名，方便应用层把用户动作映射为统一 cancel 术语。
     * 该默认实现不引入第二套状态机语义。
     *
     * @param session 要取消的模型会话
     * @param reason 脱敏后的稳定取消原因码
     * @param idempotencyKey 取消操作的稳定幂等键
     * @param context 本次调用的 deadline 和取消上下文
     * @return 与 abort 完全相同的结果
     */
    default ModelDriverResult<Void> cancel(
            ModelDriverSession session,
            String reason,
            String idempotencyKey,
            ModelOperationContext context) {
        return abort(session, reason, idempotencyKey, context);
    }

    /**
     * 释放会话资源。可重复调用；成功后句柄状态为 CLOSED。
     * 关闭不改变会议状态，会议终态由应用层决定。若 close 在 final 回调完成前
     * 抢先线性化，completeInput 必须失败且不发送 COMPLETED terminal；若模型
     * 已线性化为 COMPLETED，后续 close 只释放资源，不得把完成结果改成失败。
     * 若 close 在 final 尚未开始交付时抢先，随后完成线程即使成功交付 final，也
     * 必须先记录 final 已交付再返回终态错误；同一幂等键重试不得重复 final 或补发
     * COMPLETED terminal。
     * 若 COMPLETED 的 terminal 尚未交付，close 后仍可用原幂等键重试 terminal
     * 交付；此重试不重新执行 final 或模型输入排空。
     *
     * @param session 要释放的会话句柄
     * @param context 关闭调用上下文；即使已经取消/超时也必须尽力释放资源
     * @return 资源已释放或稳定错误
     */
    ModelDriverResult<Void> close(ModelDriverSession session, ModelOperationContext context);

    /**
     * 停止接收新会话并在 deadline 前尽量关闭现有会话。
     * 参考 Mock 用于测试进程退出时的资源回收；生产实现应执行 DRAIN。
     *
     * @param deadline 最晚允许 Driver 完成排空和释放的时间
     */
    void shutdown(Instant deadline);

    /** 系统音频缺口的处理结果。 */
    enum DiscontinuityAction {
        CONTINUED,
        PROVIDER_REOPEN_REQUIRED,
        FAILED
    }
}
