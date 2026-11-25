package com.czur.aimeeting.client

import java.io.IOException
import java.util.Optional

/** 可选应用私有停止记录端口，只在 IO 队列调用，不扫描／删除文件或自动迁移数据。 */
interface StoppedMeetingStore {
    /**
     * 同场已有记录使用 existing.merge 合并，保留先进 ACK、原 genTime／音频／Stop 键，
     * 允许正常同账号续签后更新 token。只有持久化同步成功才返回；私有附加 ACK 不可清空。
     * 实现须一并保存会前语言快照与“纪要语言已上报”：进程在发布成功、上报语言前退出时，
     * 续办要靠它们补发一次服务端纪要请求；旧记录缺字段按 null／false 读回。
     */
    @Throws(IOException::class)
    fun save(checkpoint: StoppedMeetingCheckpoint): StoppedMeetingCheckpoint
    /** 只按原 ID 读取，不存在为 empty；不从当前账号或目录音频补造记录。 */
    @Throws(IOException::class)
    fun read(checkpointId: String): Optional<StoppedMeetingCheckpoint>
}
