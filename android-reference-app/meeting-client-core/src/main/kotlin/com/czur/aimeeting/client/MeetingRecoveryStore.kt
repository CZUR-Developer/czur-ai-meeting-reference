package com.czur.aimeeting.client

import java.io.IOException
import java.util.Optional

/**
 * 可选应用私有原场现场端口，只在 IO 队列调用。
 *
 * 只负责保存、读回和删除本机现场；不扫描音频目录、不补造记录、不删除录音或
 * 字幕日志。宿主在读回时核对原账号并把展示方式等私有数据附回同一记录。
 */
interface MeetingRecoveryStore {
    /** 同一会议编号覆盖既有现场，只有持久化同步成功才返回。 */
    @Throws(IOException::class)
    fun save(record: CapturingMeeting)
    /** 只按原编号读取；不存在或其他账号返回 empty，不能从当前账号补造。 */
    @Throws(IOException::class)
    fun read(meetingId: String): Optional<CapturingMeeting>
    /** 已确认停止或主动放弃后删除；失败由宿主保留并重试。 */
    @Throws(IOException::class)
    fun delete(meetingId: String)
}
