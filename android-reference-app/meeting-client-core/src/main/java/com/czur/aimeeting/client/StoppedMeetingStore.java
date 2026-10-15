package com.czur.aimeeting.client;

import java.io.IOException;
import java.util.Optional;

/**
 * 应用可选注入的窄停止记录端口，不是实时队列、录音缓存或通用恢复平台。
 * 协调器仅在 IO 队列调用；文件编码、同步和原子替换由应用私有实现负责。
 * 默认不装配，不改变旧会议路径；此端口不扫描散落文件、不删除音频或自动迁移数据。
 */
public interface StoppedMeetingStore {
    /**
     * 首次保存原场候选；已有记录时通过 existing.merge(checkpoint) 核原事实并合并
     * 公共进度，禁止阶段／genTime 倒退或覆盖原文件、凭据、Stop 键。返回真正持久化的
     * 最先进公共记录；只有同步成功才正常返回，否则抛 IOException，后续外部步骤不推进。
     * 私有实现须在同一记录中保留已有登记 ACK／prepareId 等附加事实，不能因为公共保存
     * 重写或清空它们。私有发布器的 ACK 更新亦须遵守同一规则。
     */
    StoppedMeetingCheckpoint save(StoppedMeetingCheckpoint checkpoint) throws IOException;

    /**
     * 按原 checkpointId 读回公共记录；不存在返回 empty，不能据当前账号或目录中的
     * 音频文件补造。应用决定哪些私有条件已齐的记录可续办，未确认的私有登记另留待处理。
     */
    Optional<StoppedMeetingCheckpoint> read(String checkpointId) throws IOException;
}
