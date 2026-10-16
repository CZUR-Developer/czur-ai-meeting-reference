package com.czur.aimeeting.client.server

/** 匿名发现事实；语言保持后端顺序，不从 Adapter 推断未声明语言，本场仍以 Create 为准。 */
data class DeploymentOptions @JvmOverloads constructor(private var capabilities: Set<String>,
    private val resultLinkTtlMs: Long, private val resultEmailEnabled: Boolean,
    private var languages: List<String> = emptyList()) {
    init {
        capabilities = java.util.Set.copyOf(capabilities)
        languages = java.util.List.copyOf(languages)
    }
    fun capabilities(): Set<String> = capabilities
    fun resultLinkTtlMs(): Long = resultLinkTtlMs
    fun resultEmailEnabled(): Boolean = resultEmailEnabled
    fun languages(): List<String> = languages
}
