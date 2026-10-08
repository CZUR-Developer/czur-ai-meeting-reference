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
     */
    void submitPresentation(long offsetMs, byte[] jpeg);
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
