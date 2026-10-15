package com.czur.aimeeting.model;

import java.io.IOException;

/**
 * 已创建会议的窄模型续接端口，由会议组合层捕获本场 Server／身份及 meetingId。
 *
 * <p>只重新领取原场模型材料并从注册工厂创建新 Adapter，不再 Create／StartClient，
 * 不重读当前账号，也不向 App 暴露模型原始协议。调用可能阻塞 HTTP，Runtime 必须
 * 放在自己的续接工作线程，不能在主线程、采音回调、状态锁或音源控制队列中调用。</p>
 *
 * <p>停止或显式关闭后，Runtime 应取消待执行重试并丢弃迟到结果；不能等待本方法
 * 返回后才释放音源。具体网络取消和重试仍归 Runtime，此接口不建立持久恢复任务。</p>
 */
@FunctionalInterface
public interface ModelSessionReconnector {
    /**
     * 读取原业务会议的新连接材料与新 Adapter。每次成功返回都属于新的模型连接代次。
     *
     * @return 同场完整连接材料与新的协议解析实例；不能复用旧实例或伪造模型完成。
     * @throws IOException 本次材料未能取得；是否再次尝试由仍在运行的 Runtime 决定。
     */
    ModelSessionConnection request() throws IOException;
}
