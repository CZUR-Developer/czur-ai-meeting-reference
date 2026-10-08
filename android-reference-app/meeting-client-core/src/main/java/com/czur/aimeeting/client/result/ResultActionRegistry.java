package com.czur.aimeeting.client.result;

import com.czur.aimeeting.client.MeetingClient;
import com.czur.aimeeting.client.MeetingDiscovery;
import com.czur.aimeeting.client.MeetingOptions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * 普通构建期结果动作注册表：传入的清单就是产品开放清单，不默认添加任何插件。
 * 声明、准备与派发不依赖Android／具体动作ID；注册表不持有会议，不重新Stop、不自动重试。
 * App会前准备一次，在真实READY通知后执行。独立失败结果不会改动原MeetingResult。
 */
public final class ResultActionRegistry {
    public static final String RESULT_LINK = "result.link";
    public static final String RESULT_EMAIL = "result.email";
    private static final Logger LOG = Logger.getLogger(ResultActionRegistry.class.getName());
    private final Map<String, ResultActionPlugin> plugins;

    /** 清单顺序供页面使用；在创建页面／开会前装配，不在会中动态注册。 */
    public ResultActionRegistry(List<? extends ResultActionPlugin> plugins) {
        Map<String, ResultActionPlugin> registered = new LinkedHashMap<>();
        for (ResultActionPlugin plugin : plugins) {
            if (plugin.id().isBlank() || registered.putIfAbsent(plugin.id(), plugin) != null)
                throw new IllegalArgumentException("结果动作ID为空或重复");
        }
        this.plugins = java.util.Collections.unmodifiableMap(registered);
    }

    /** 只列出已注册且满足后端服务的实现；插件自己的输入配置在prepare检查。 */
    public List<ResultActionPlugin> available(MeetingDiscovery discovery) {
        Set<String> services = serviceCapabilities(discovery);
        List<ResultActionPlugin> available = new ArrayList<>();
        for (ResultActionPlugin plugin : plugins.values()) {
            if (services.containsAll(plugin.requiredServiceCapabilities())) available.add(plugin);
        }
        return List.copyOf(available);
    }

    /** 适配已有发现字段，不改公开HTTP协议，不与模型可执行能力求交。 */
    public static Set<String> serviceCapabilities(MeetingDiscovery discovery) {
        var deployment = discovery.deployment();
        java.util.HashSet<String> services = new java.util.HashSet<>();
        if (deployment.resultLinkTtlMs() > 0) services.add(RESULT_LINK);
        if (deployment.resultEmailEnabled()) services.add(RESULT_EMAIL);
        return Set.copyOf(services);
    }

    /**
     * 在会前Hook主线程冻结一次；缺实现／服务／本地准备状态返回理由，不创建会议。
     * requiredArtifactTypes仅冻结声明，会后再对照真实READY，不能在会前声称产物已存在。
     */
    public Preparation prepare(String actionId, MeetingOptions options, MeetingDiscovery discovery) {
        ResultActionPlugin plugin = plugins.get(actionId);
        if (plugin == null) return new Preparation(null, "当前应用未装配所选结果动作");
        if (!serviceCapabilities(discovery).containsAll(plugin.requiredServiceCapabilities()))
            return new Preparation(null, "当前部署尚未提供所选结果服务");
        ResultActionPlugin.Preparation prepared = plugin.prepare(options);
        if (!prepared.allowed()) return new Preparation(null, prepared.message());
        return new Preparation(new PreparedAction(plugin.id(), plugin.requiredArtifactTypes(),
                plugin.requiredServiceCapabilities(), prepared.execution()), "");
    }

    /**
     * 在宿主UI队列检查真实产物／服务并通用派发；完成通知也串行排到同一UI队列。
     * 普通异步onError或执行异常转为独立FAILED提示，宿主可显式再次调用本方法。
     * 注册表不管理长驻任务或恢复；宿主在动作完成或结束结果交互后释放原client和队列。
     */
    public void execute(PreparedAction action, ResultActionContext context,
            MeetingClient.Callback<ResultActionOutcome> callback) {
        Objects.requireNonNull(action, "action");
        AtomicBoolean completed = new AtomicBoolean();
        MeetingClient.Callback<ResultActionOutcome> completion = new MeetingClient.Callback<>() {
            @Override public void onSuccess(ResultActionOutcome outcome) {
                if (!completed.compareAndSet(false, true)) return;
                LOG.info("结果动作完成 id=" + action.id() + " status=" + outcome.status());
                context.uiExecutor().execute(() -> callback.onSuccess(
                        new ResultActionOutcome(action.id(), outcome.status(), outcome.message())));
            }
            @Override public void onError(String message) {
                onSuccess(ResultActionOutcome.failure(action.id(), message));
            }
        };
        context.uiExecutor().execute(() -> {
            if (!context.result().artifactTypes().containsAll(action.requiredArtifactTypes())) {
                completion.onError("本场未产生所选动作需要的结果产物");
                return;
            }
            if (!context.serviceCapabilities().containsAll(action.requiredServiceCapabilities())) {
                completion.onError("当前部署尚未提供所选结果服务");
                return;
            }
            LOG.info("结果动作开始 id=" + action.id());
            try { action.execution().execute(context, completion); }
            catch (RuntimeException failure) {
                LOG.warning("结果动作失败 id=" + action.id() + " type=" + failure.getClass().getSimpleName());
                completion.onError("结果动作执行失败，请再次执行");
            }
        });
    }

    /** 冻结的单场动作声明与参数闭包；只在当前进程持有。 */
    public record PreparedAction(String id, Set<String> requiredArtifactTypes,
            Set<String> requiredServiceCapabilities, ResultActionPlugin.Execution execution) {
        public PreparedAction {
            Objects.requireNonNull(id, "id");
            requiredArtifactTypes = Set.copyOf(requiredArtifactTypes);
            requiredServiceCapabilities = Set.copyOf(requiredServiceCapabilities);
            Objects.requireNonNull(execution, "execution");
        }
    }
    public record Preparation(PreparedAction action, String message) {
        public boolean allowed() { return action != null; }
    }
}
