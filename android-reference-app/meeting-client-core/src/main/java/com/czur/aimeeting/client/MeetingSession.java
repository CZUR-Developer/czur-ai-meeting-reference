package com.czur.aimeeting.client;

import com.czur.aimeeting.client.result.ResultActionAccess;

/** 一场会议的业务控制；控制方法可从 UI/截图工作线程调用，内部统一排队。 */
public interface MeetingSession extends AutoCloseable {
    /**
     * 正常停止：beforeStop → 停模型输入 → 等最终事件 → 总结/Stop → 实际 READY/交付。
     * 仅 onState.canStop 为 true 时受理；Stop 202 对应 STOP_PENDING，可再次确认，
     * 不重复最后截图衔接、停音源或已提交的纪要。TRACELESS 以 ENDED 结束，不查询结果。
     */
    void requestStop();
    /**
     * offsetMs 相对 onStarted.startedAtMs，单位毫秒；方法调用时复制 JPEG，
     * 序号由组合层分配。仅本场启用 presentationCapture 且未完成停止衔接时接纳。
     * 此旧入口只表示已排队，不表示后端已保存；需要更新去重基线或等待末帧确认时，
     * 请使用带 Callback 的重载。单帧保存失败仅提示，不阻断字幕和正常 Stop。
     */
    void submitPresentation(long offsetMs, byte[] jpeg);
    /**
     * 异步提交同场的一帧 JPEG，内建 Coordinator 仅在后端确认包序号、单帧数量和
     * 产物 ID 后调用 {@code onSuccess(null)}；失败调用 {@code onError}，不伪造保存成功。
     * 每次调用的结果最多交付一次，通知使用配置的串行 callbackExecutor。
     *
     * <p>JPEG 在方法调用时复制，编码与网络在独立 IO 队列执行。请求及服务端规范化
     * 保存正文（含 schemaVersion）任一超出既有1MiB预算时，先报错且不保留图片
     * 或消耗序号。一次上传未确认时，仅在内存保留
     * 一个原序号／原字节包；下一次提交先确认该包，再保存本次新图，不自动定时重试。
     * App 应只在成功回调后更新去重基线；停止前末帧的成功或失败回调都应续接
     * beforeStop 的 continuation，不能在 UI 线程阻塞等待。</p>
     *
     * <p>默认方法保留旧自定义 Session 的源码兼容；未实现 ACK 的 Session 明确抛出
     * UnsupportedOperationException，不通过调用旧 void 方法伪造成功确认。</p>
     *
     * @param offsetMs 相对本场 MeetingStarted.startedAtMs 的毫秒位置
     * @param jpeg 由 App／系统截图能力生成的 JPEG；调用方随后可复用原数组
     * @param callback 当前帧的保存确认；成功值为 null，错误不改变字幕结果状态
     */
    default void submitPresentation(long offsetMs, byte[] jpeg,
            MeetingClient.Callback<Void> callback) {
        throw new UnsupportedOperationException("当前会议实现未提供演示截图保存确认");
    }
    /** 交换同一显示快照中的呈现位置，语义 A/B 和真实语言不变。 */
    void swapDisplayColumns();
    /**
     * 获取本场结果动作窄访问；真正请求在READY后受理，不重新读取当前身份、不重新Stop。
     * 返回对象可以在会前取得，但提前请求会得到类型化错误；关闭本场后不能继续访问。
     * 默认方法保留旧自定义Session源码兼容，未支持的实现明确报错而不伪造成功。
     */
    default ResultActionAccess resultActionAccess() {
        throw new UnsupportedOperationException("当前会议实现未提供结果动作访问");
    }
    /** 幂等取消释放，不产生正常 Stop 或结果；关闭后不再派发此场尚未执行的通知。 */
    @Override void close();
}
