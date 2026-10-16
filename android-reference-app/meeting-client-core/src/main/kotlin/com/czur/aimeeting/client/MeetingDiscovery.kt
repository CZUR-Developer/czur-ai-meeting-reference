package com.czur.aimeeting.client

import com.czur.aimeeting.client.server.DeploymentOptions

/** 部署事实与客户端可执行能力交集；App 负责呈现，不选择 Adapter。 */
data class MeetingDiscovery(
    private val deployment: DeploymentOptions,
    private var availableCapabilities: Set<String>,
) {
    init {
        availableCapabilities = java.util.Set.copyOf(availableCapabilities)
    }
    fun deployment(): DeploymentOptions = deployment
    fun availableCapabilities(): Set<String> = availableCapabilities
}
