package com.czur.aimeeting.client

/** 部署方配置的档位引用，本场授权以 Create 为准。 */
data class MeetingProfile(
    private val id: String,
    private val revision: Int,
) {
    fun id(): String = id
    fun revision(): Int = revision
    companion object {
        @JvmStatic fun reference(): MeetingProfile = MeetingProfile("reference-asr", 1)
    }
}
