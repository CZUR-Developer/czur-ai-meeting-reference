package com.czur.aimeeting.client.server

import com.czur.aimeeting.client.MeetingCapabilityState
import com.czur.aimeeting.model.ModelSessionGrant

/** Create 的标准结果；Mock 可缺省模型材料，真实能力和时长均由 Server 冻结。 */
class CreatedMeeting(private val meetingId: String, private val modelSession: ModelSessionGrant?,
    private val capabilities: MeetingCapabilityState, private val maxMeetingDurationMs: Long) {
    fun meetingId(): String = meetingId
    fun modelSession(): ModelSessionGrant? = modelSession
    fun capabilities(): MeetingCapabilityState = capabilities
    fun maxMeetingDurationMs(): Long = maxMeetingDurationMs
    fun withModelSession(grant: ModelSessionGrant?): CreatedMeeting =
        CreatedMeeting(meetingId, grant, capabilities, maxMeetingDurationMs)
}
