package com.czur.aimeeting.client;

import com.czur.aimeeting.client.server.DeploymentOptions;
import java.util.Set;

/** 部署事实与本客户端可执行能力的交集；App 只决定呈现，不选择具体 Adapter。 */
public record MeetingDiscovery(DeploymentOptions deployment, Set<String> availableCapabilities) {
    public MeetingDiscovery { availableCapabilities = Set.copyOf(availableCapabilities); }
}
