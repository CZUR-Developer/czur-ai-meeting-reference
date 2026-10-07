package com.czur.aimeeting.client.server;

import java.util.Set;
import java.util.List;

/**
 * 匿名部署发现信息；languages保持后端声明的顺序，App直接据此显示语言选项。
 * 语言清单与本地Adapter可执行能力一起供会前设置使用，本场允许能力仍以Create响应为准。
 */
public record DeploymentOptions(Set<String> capabilities, long resultLinkTtlMs,
        boolean resultEmailEnabled, List<String> languages) {
    /** 兼容旧测试／调用方；未声明语言时不替部署推断可用语言。 */
    public DeploymentOptions(Set<String> capabilities, long resultLinkTtlMs,
            boolean resultEmailEnabled) {
        this(capabilities, resultLinkTtlMs, resultEmailEnabled, List.of());
    }
    public DeploymentOptions {
        capabilities = Set.copyOf(capabilities);
        languages = List.copyOf(languages);
    }
}
