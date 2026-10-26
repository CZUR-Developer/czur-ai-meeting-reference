package com.czur.reference.application.http;

import com.czur.reference.application.command.MeetingCommandContext;
import com.czur.reference.application.port.MeetingHeartbeatStore;
import com.czur.reference.application.port.MeetingQueryPort;
import com.czur.reference.domain.meeting.MeetingState;

import java.time.Clock;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 会议心跳的 transport-neutral HTTP 端点。
 *
 * <p>认证由外层 {@code MeetingHttpDispatcher} 走既有 CLIENT_WRITE 路线完成；
 * 本类只接收已验证上下文，先用现有查询端口核对会议存在性与归属（跨主体和跨
 * 部署仍由查询端口返回统一的拒绝结果），再要求会议处于 {@code STARTING} 或
 * {@code RUNNING}。其它状态沿用 Stop／模型会话一致的 409 会话域错误，不区分
 * “会议不存在”和“状态不符”之外的新语义。</p>
 *
 * <p>成功时只回告部署配置冻结的心跳间隔，不返回状态、活跃时间或计数；持久化
 * 由 {@link MeetingHeartbeatStore} 用短事务完成（锁读会议父行、复核活跃条件，
 * 再 UPSERT 心跳行），重复调用只是刷新最后活跃时间。心跳事实供弃场扫描决定
 * 窗口与结算时长；本类本身不推进会议状态、不结算用量，也不读取客户端上报的
 * 时间。会议已离开 {@code STARTING}／{@code RUNNING} 或已落弃场决定时，写入
 * 端口返回 false，本类回 409。</p>
 *
 * <p>日志只记录固定故障类别，不记录会议标识、主体、状态或异常正文。</p>
 */
public final class MeetingHeartbeatEndpoint {
    private static final Logger LOGGER = Logger.getLogger(
            MeetingHeartbeatEndpoint.class.getName());
    private static final Pattern PATH = Pattern.compile(
            "^/v1/meetings/(mtg_[A-Za-z0-9_-]+):heartbeat$");

    private final MeetingQueryPort queryPort;
    private final MeetingHeartbeatStore store;
    private final Clock clock;
    private final int heartbeatIntervalMs;
    private final HttpErrorMapper errors;

    /**
     * @param queryPort 与既有会议查询、Stop 共用的权威事实端口
     * @param store 只写心跳行的窄端口
     * @param clock 服务端时钟；客户端不得提交时间
     * @param heartbeatIntervalMs 部署配置冻结的期望间隔，必须是正数
     * @param errors 与运行时共享的错误映射器
     */
    public MeetingHeartbeatEndpoint(MeetingQueryPort queryPort, MeetingHeartbeatStore store,
            Clock clock, int heartbeatIntervalMs, HttpErrorMapper errors) {
        this.queryPort = Objects.requireNonNull(queryPort, "queryPort");
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (heartbeatIntervalMs <= 0) {
            throw new IllegalArgumentException("heartbeatIntervalMs must be positive");
        }
        this.heartbeatIntervalMs = heartbeatIntervalMs;
        this.errors = Objects.requireNonNull(errors, "errors");
    }

    /** 仅匹配单场会议心跳写入口；其它方法或路径不触发身份解析。 */
    public static boolean matches(HttpRequest request) {
        return "POST".equals(request.method())
                && PATH.matcher(request.path()).matches();
    }

    /**
     * 处理一次已认证心跳请求。
     *
     * @param request 已由 transport 约束的请求值对象
     * @param context dispatcher 解析出的可信客户端写上下文
     */
    public HttpResponse handle(HttpRequest request, MeetingCommandContext context) {
        try {
            Objects.requireNonNull(request, "request");
            Matcher path = PATH.matcher(request.path());
            if (context == null || !matches(request) || !path.matches()) {
                return context == null ? errors.missingContext() : errors.invalidRoute();
            }
            if (!request.query().isEmpty() || !request.body().isEmpty()) {
                throw new IllegalArgumentException("heartbeat request must have no body or query");
            }
            String meetingId = path.group(1);
            MeetingQueryPort.Result meeting = queryPort.get(
                    new MeetingQueryPort.GetRequest(context, meetingId));
            if (!(meeting instanceof MeetingQueryPort.Found found)) {
                return errors.query(meeting);
            }
            MeetingState state = found.meeting().state();
            if (state != MeetingState.STARTING && state != MeetingState.RUNNING) {
                return errors.heartbeatInvalidState();
            }
            try {
                if (!store.record(meetingId, clock.instant())) {
                    // 查询与写入之间会议已离开 STARTING/RUNNING，或已落弃场决定。
                    return errors.heartbeatInvalidState();
                }
            } catch (RuntimeException unavailable) {
                LOGGER.log(Level.FINE, "会议心跳记录失败 type={0}",
                        unavailable.getClass().getSimpleName());
                return errors.heartbeatUnavailable();
            }
            return errors.heartbeat(heartbeatIntervalMs);
        } catch (RuntimeException failure) {
            return errors.exception(failure);
        }
    }
}
