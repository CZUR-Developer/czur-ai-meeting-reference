package com.czur.aimeeting.client.server;

import java.util.Set;

/** 匿名部署发现信息；本场允许能力仍以 Create 响应为准。 */
public record DeploymentOptions(Set<String> capabilities, long resultLinkTtlMs,
        boolean resultEmailEnabled) {
    public DeploymentOptions { capabilities = Set.copyOf(capabilities); }
}
