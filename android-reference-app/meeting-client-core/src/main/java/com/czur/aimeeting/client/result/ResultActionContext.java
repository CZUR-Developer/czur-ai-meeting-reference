package com.czur.aimeeting.client.result;

import com.czur.aimeeting.client.MeetingResult;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * 会后动作的不可变上下文。result必须是SDK返回的同场READY事实，access取该场Session。
 * App提供工作队列与串行UI队列；Android页面／文件属于插件，不进入公共core。
 * uiExecutor可由宿主在Activity可呈现时执行，不得因此重开音源或改会议状态。
 * 每次手动执行复用相同result／access／冻结参数，宿主负责显示动作结果并最终释放资源。
 */
public record ResultActionContext(MeetingResult result, ResultActionAccess access,
        Set<String> serviceCapabilities, Executor workExecutor, Executor uiExecutor) {
    public ResultActionContext {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(access, "access");
        serviceCapabilities = Set.copyOf(serviceCapabilities);
        Objects.requireNonNull(workExecutor, "workExecutor");
        Objects.requireNonNull(uiExecutor, "uiExecutor");
    }
}
