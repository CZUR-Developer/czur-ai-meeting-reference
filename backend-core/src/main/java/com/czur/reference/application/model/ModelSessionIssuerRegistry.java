package com.czur.reference.application.model;

import java.time.Clock;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 按 Adapter ID 选择模型发行器的注册表。
 *
 * <p>内置发行器仍由参考装配注册；合作伙伴实现自己的 {@link ModelSessionIssuer} 后，
 * 在启动前 {@code register("my-asr", ctx -> new MyIssuer(...))}，再把
 * {@code AI_MEETING_MODEL_ADAPTER} 写成同一个 ID 即可，不需要修改参考实现的配置校验
 * 或装配分支。</p>
 *
 * <p>注册表只解决"接入"，不产生模型能力：ID 必须同时有 Android 侧 {@code ModelAdapter}
 * 和这里注册的发行器，否则开会时拿不到本场材料。</p>
 */
public final class ModelSessionIssuerRegistry {
    /** 发行器工厂；每次创建新实例，可读取部署配置与时钟。 */
    public interface Factory {
        ModelSessionIssuer create(Context context);
    }

    /** 工厂可见的最小上下文：原始部署环境与时钟。 */
    public record Context(Map<String, String> environment, Clock clock) {
        public Context {
            environment = Collections.unmodifiableMap(new LinkedHashMap<>(environment));
            Objects.requireNonNull(clock, "clock");
        }
    }

    private final Map<String, Factory> factories = new LinkedHashMap<>();

    /** 注册一个 Adapter ID；同一 ID 重复注册视为配置错误，不静默覆盖。 */
    public synchronized ModelSessionIssuerRegistry register(String adapterId, Factory factory) {
        Objects.requireNonNull(factory, "factory");
        String id = requireAdapterId(adapterId);
        if (factories.putIfAbsent(id, factory) != null) {
            throw new IllegalArgumentException("模型 Adapter 已注册: " + id);
        }
        return this;
    }

    public synchronized boolean contains(String adapterId) {
        return adapterId != null && factories.containsKey(adapterId);
    }

    /** 已注册的 Adapter ID；用于启动日志和排错，不含任何凭据。 */
    public synchronized Set<String> adapterIds() {
        return Set.copyOf(factories.keySet());
    }

    /**
     * 按 ID 创建发行器；未注册时给出可执行的错误，而不是空指针或"必须配置"这类笼统提示。
     */
    public synchronized ModelSessionIssuer create(String adapterId, Context context) {
        Objects.requireNonNull(context, "context");
        String id = requireAdapterId(adapterId);
        Factory factory = factories.get(id);
        if (factory == null) {
            throw new IllegalArgumentException(
                    "未注册的模型 Adapter: " + id + "；已注册: " + factories.keySet()
                            + "。自定义模型需在启动前调用 ModelSessionIssuerRegistry.register 注册。");
        }
        return Objects.requireNonNull(factory.create(context), "issuer");
    }

    private static String requireAdapterId(String adapterId) {
        if (adapterId == null || adapterId.isBlank() || !adapterId.equals(adapterId.trim())) {
            throw new IllegalArgumentException("modelAdapterId");
        }
        return adapterId;
    }
}
