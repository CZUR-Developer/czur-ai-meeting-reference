package com.czur.aimeeting.model;

import java.io.IOException;
import java.util.Optional;
import java.util.OptionalLong;

/** 一次模型会话；start 后产生事件，stopInput 后仍接收模型最终结果。 */
public interface ModelSession extends AutoCloseable {
    /** 开始本次会话的数据处理。 */
    void start();

    /** 停止采集/发送音频并发送 Adapter 的停止消息；最终结果经监听器返回。 */
    void stopInput();

    /**
     * 读取本场已停止采音后的累计时长，单位为整数毫秒。
     *
     * <p>实现方在唯一音源停止、完整在途尾帧交付结束后一次冻结；标准
     * 16 kHz／单声道／PCM16 的口径为实际交付字节数除以 32。静音和连接模型前
     * 已采帧计入，模型重发及总结等待不计入。正常 stopInput 返回且模型最终完成后
     * 可读取；取消／错误路径也可以提供已冻结值，读取不会改变会话状态。
     *
     * <p>本值支持跨线程读取，冻结后终身不变。empty 表示尚未冻结或该实现不提供
     * 用量，不能将它当作零；0 仅表示已经停止并确认没有交付任何完整音频帧。
     * 默认 empty 让既有模型／Mock 实现保持兼容，不虚构实采用量。
     */
    default OptionalLong frozenAudioDurationMs() { return OptionalLong.empty(); }

    /**
     * 读取同源录音已经完成封口的不可变事实。正常 stopInput 返回、完整尾帧排完并且
     * 编码/文件同步成功后才可提供，冻结后同场重复读取不重开录音或重新 seal。
     * empty 表示未装配或尚未封口，不等于已确认零帧；封口失败须抛 IOException，
     * 不能返回失败文件并伪造成功。取消路径不产生正常可发布事实。
     * 默认 empty 保留既有实现兼容；已明确选择保留音频的协调器不会将 empty 当作成功。
     */
    default Optional<SealedAudioArtifact> sealedAudioArtifact() throws IOException {
        return Optional.empty();
    }

    /** 取消并释放资源。调用方不再需要本次会话的后续事件。 */
    @Override
    void close();
}
