package com.czur.reference.application.port;

import java.util.List;

/**
 * 列出本部署中等待输入接管的会议，供进程重启后的周期扫描使用。
 *
 * <p>每次只返回 {@code afterMeetingId} 之后、按会议 ID 升序排列的有限一页。
 * 这里返回的是候选，不是收口许可：调用方必须逐个交给会议级 Reconciler，
 * 由它重新读取 Stop、完整音频和冻结计划的权威事实。候选查询失败应抛出异常，
 * 不可伪装成空页。</p>
 */
public interface PendingMeetingInputPort {
    List<String> listAfter(String afterMeetingId, int limit);
}
