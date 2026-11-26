package com.czur.aimeeting.client

import com.czur.aimeeting.model.SealedAudioArtifact
import java.io.IOException

/**
 * Optional durable extension for an explicit user Stop. The host keeps this alongside
 * the original capturing record and owns its account, deployment and file checks.
 */
interface MeetingStopIntentStore : MeetingRecoveryStore {
    /** Opt in only where the app can reopen the original journal and the service can confirm its events. */
    fun usesDurableStandardEvents(): Boolean = false
    /** An original live owner can revoke event transmission and local ACK without losing the raw packet. */
    @Throws(IOException::class)
    fun checkStandardEventScope() {}

    /** Optional service-specific capability acquired while the original account can still start this meeting. */
    @Throws(IOException::class)
    fun prepareStopGrant(meetingId: String, accessToken: String): MeetingStopGrant? = null

    /** Normal in-process automatic Stop may use a grant while the opening account still owns the host. */
    fun canUseStopGrantAtCurrentLogin(): Boolean = false

    /** Atomically record the request and return the existing key on replay. */
    @Throws(IOException::class)
    fun beginExplicitStop(meetingId: String): String

    /** Commit exact frozen input facts before a usage request is allowed. */
    @Throws(IOException::class)
    fun saveFrozenStop(meetingId: String, stop: MeetingStopIntent)

    /** An old capturing record has no explicit Stop. Invalid data must fail the read. */
    @Throws(IOException::class)
    fun readExplicitStop(meetingId: String): MeetingStopIntent?
}

class MeetingStopGrant(val stopKey: String, val capability: String) {
    init {
        require(stopKey.isNotBlank() && capability.isNotBlank())
    }

    override fun toString(): String = "MeetingStopGrant[redacted]"
}

class MeetingStopIntent(
    val stopKey: String,
    val frozenUsageMs: Long? = null,
    val sealedAudio: SealedAudioArtifact? = null,
) {
    init {
        require(stopKey.isNotBlank())
        require(frozenUsageMs == null || frozenUsageMs >= 0)
        require(sealedAudio == null || sealedAudio.durationMs() == frozenUsageMs)
    }
}
