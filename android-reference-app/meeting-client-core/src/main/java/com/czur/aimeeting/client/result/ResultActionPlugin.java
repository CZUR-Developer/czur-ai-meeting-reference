package com.czur.aimeeting.client.result;

import com.czur.aimeeting.client.MeetingClient;
import com.czur.aimeeting.client.MeetingOptions;
import java.util.Objects;
import java.util.Set;

/**
 * 构建期注册的结果动作。SDK提供结果事实和窄访问，平台UI／文件／外部应用由App实现。
 * 实现类不接收token、原后端client、模型材料或音频；声明服务不使未注册实现自动出现。
 * 伙伴只增加实现类及装配，不修改音源、模型通信或会议Stop。
 */
public interface ResultActionPlugin {
    /** 产品内长期稳定的字符串ID，例如web或partner.notice；不使用显示文案作键。 */
    String id();
    String displayName();
    /** 实际READY清单必须具备的产物；Web／纯提示可为空，不从会前模式伪造产物。 */
    default Set<String> requiredArtifactTypes() { return Set.of(); }
    /** 对应部署已提供的服务ID；模型能力与结果服务是两类事实。 */
    default Set<String> requiredServiceCapabilities() { return Set.of(); }

    /**
     * App在既有会前Hook中调用，只读本地已准备状态并返回本场执行闭包。
     * 在Android主线程调用，不同步联网或等待扫码；网络准备由App提前异步完成。
     * 允许时只捕获不可变参数与Application Context，不能让会后重新读EditText／当前账号。
     * 返回阻止时显示message、不创建会议；本检查不能替代后端身份／权益决定。
     */
    Preparation prepare(MeetingOptions options);

    /**
     * 每次调用代表用户的一次执行；失败后可用同一闭包／READY手动再执行。
     * 注册表在context.uiExecutor调用本方法；耗时平台工作交workExecutor，协议交access。
     * 异步完成时恰好调用一次callback，message只提供本地用户提示，不能泄露原始响应。
     */
    @FunctionalInterface
    interface Execution {
        void execute(ResultActionContext context, MeetingClient.Callback<ResultActionOutcome> callback);
    }

    /** 准备状态只在当前进程传递，不序列化平台闭包，不承诺进程重建恢复。 */
    record Preparation(Execution execution, String message) {
        public Preparation { Objects.requireNonNull(message, "message"); }
        public boolean allowed() { return execution != null; }
        public static Preparation allow(Execution execution) {
            return new Preparation(Objects.requireNonNull(execution, "execution"), "");
        }
        public static Preparation block(String message) { return new Preparation(null, message); }
    }
}
