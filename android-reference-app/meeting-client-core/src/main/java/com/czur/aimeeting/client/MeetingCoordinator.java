package com.czur.aimeeting.client;

import com.czur.aimeeting.client.display.MeetingDisplaySnapshot;
import com.czur.aimeeting.client.display.MeetingDisplayState;
import com.czur.aimeeting.client.result.ResultActionAccess;
import com.czur.aimeeting.client.server.CreatedMeeting;
import com.czur.aimeeting.client.server.HttpMeetingServerClient;
import com.czur.aimeeting.client.server.HttpMeetingSessionClient;
import com.czur.aimeeting.client.server.MeetingServerClient;
import com.czur.aimeeting.client.identity.IdentityResult;
import com.czur.aimeeting.client.identity.IdentitySession;
import com.czur.aimeeting.client.identity.ReferenceIdentityProvider;
import com.czur.aimeeting.model.AudioDirectionSample;
import com.czur.aimeeting.model.AudioInputErrorCode;
import com.czur.aimeeting.model.AudioInputMode;
import com.czur.aimeeting.model.AudioInputLevel;
import com.czur.aimeeting.model.ModelAdapter;
import com.czur.aimeeting.model.ModelSession;
import com.czur.aimeeting.model.ModelSessionGrant;
import com.czur.aimeeting.model.ModelSessionListener;
import com.czur.aimeeting.model.ModelSessionConnection;
import com.czur.aimeeting.model.ModelSessionReconnector;
import com.czur.aimeeting.model.SegmentStartedEvent;
import com.czur.aimeeting.model.SealedAudioArtifact;
import com.czur.aimeeting.model.SummaryAdapter;
import com.czur.aimeeting.model.SummaryInput;
import com.czur.aimeeting.model.SummarySessionGrant;
import com.czur.aimeeting.model.TranscriptEvent;
import com.czur.aimeeting.model.TranslationEvent;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * 平台无关、唯一的正常会议编排。状态/显示/deadline 只在 state 队列访问；
 * 阻塞 HTTP及总结在独立 io 队列；模型start/stop/close在modelControl队列保持次序，
 * 不被挂起的标准事件HTTP拖住停源。SDK 和 Mock 只注入运行时。
 * 同一客户端同时只持有一场会议；关闭后不可再使用。未确认标准事件只在本场内存中
 * 保留原包，由后续事件或正常停止顺序补交；没有磁盘缓存、定时重试或跨进程恢复框架。
 */
public final class MeetingCoordinator implements MeetingClient {
    private static final Logger LOG = Logger.getLogger(MeetingCoordinator.class.getName());
    private static final java.util.regex.Pattern HTTP_CODE = java.util.regex.Pattern.compile("HTTP[ =:]*(\\d{3})");
    private final MeetingClientConfig config;
    private final ModelAdapterRegistry adapters;
    private final MeetingRuntimePort runtime;
    private final Function<MeetingClientConfig, MeetingServerClient> servers;
    private final LongSupplier monotonicMs;
    private final LongSupplier wallClockMs;
    private final ScheduledExecutorService state = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService modelControl = Executors.newSingleThreadExecutor();
    private volatile boolean closed;
    private Session current;

    public MeetingCoordinator(MeetingClientConfig config, ModelAdapterRegistry adapters,
            MeetingRuntimePort runtime) {
        this(config, adapters, runtime, HttpMeetingServerClient::new,
                runtime::elapsedRealtimeMs, System::currentTimeMillis);
    }

    /** 同包定向测试使用可控时钟/Server 端口，生产仍复用第一片 HTTP 实现。 */
    MeetingCoordinator(MeetingClientConfig config, ModelAdapterRegistry adapters,
            MeetingRuntimePort runtime, Supplier<MeetingServerClient> servers,
            LongSupplier monotonicMs, LongSupplier wallClockMs) {
        this(config, adapters, runtime, ignored -> servers.get(), monotonicMs, wallClockMs);
    }

    /** 身份测试可以直接核对传入Server的本场配置，生产仍使用同一HTTP实现。 */
    MeetingCoordinator(MeetingClientConfig config, ModelAdapterRegistry adapters,
            MeetingRuntimePort runtime, Function<MeetingClientConfig, MeetingServerClient> servers,
            LongSupplier monotonicMs, LongSupplier wallClockMs) {
        this.config = Objects.requireNonNull(config, "config");
        this.adapters = Objects.requireNonNull(adapters, "adapters");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.servers = Objects.requireNonNull(servers, "servers");
        this.monotonicMs = Objects.requireNonNull(monotonicMs, "monotonicMs");
        this.wallClockMs = Objects.requireNonNull(wallClockMs, "wallClockMs");
    }

    private Set<String> setupCapabilities() {
        return runtime.usesServerModelMaterials() ? adapters.setupCapabilities()
                : Set.copyOf(runtime.localCapabilities());
    }

    @Override public void discover(Callback<MeetingDiscovery> callback) {
        requireOpen();
        io.execute(() -> {
            try {
                LOG.info("部署能力发现开始");
                var deployment = servers.apply(config).discover();
                Set<String> available = new HashSet<>(deployment.capabilities());
                available.retainAll(setupCapabilities());
                LOG.info("部署能力发现完成");
                notifyClient(() -> callback.onSuccess(new MeetingDiscovery(deployment, available)));
            } catch (IOException | RuntimeException failure) {
                logFailure("部署能力发现", failure);
                notifyClient(() -> callback.onError("读取部署能力失败"));
            }
        });
    }

    @Override public void refreshIdentity(Callback<IdentityResult> callback) {
        requireOpen();
        io.execute(() -> {
            IdentityResult identity = readIdentity();
            LOG.fine("当前身份读取完成 state=" + identity.status());
            notifyClient(() -> callback.onSuccess(identity));
        });
    }

    private IdentityResult readIdentity() {
        try {
            return config.identityProvider().currentIdentity();
        } catch (IOException | RuntimeException failure) {
            logFailure("身份读取", failure);
            return IdentityResult.readFailed("读取登录状态失败，请稍后重试");
        }
    }

    @Override public void login(String username, String password,
            Callback<com.czur.aimeeting.client.server.ReferenceLoginSession> callback) {
        requireOpen();
        io.execute(() -> {
            if (!(config.identityProvider() instanceof ReferenceIdentityProvider)) {
                notifyClient(() -> callback.onError("请使用当前身份来源的应用登录入口"));
                return;
            }
            try {
                LOG.info("终端用户登录开始");
                var login = new HttpMeetingSessionClient(config.serverUrl()).login(username, password);
                ((ReferenceIdentityProvider) config.identityProvider()).updateSession(
                        new IdentitySession(login.accessToken(), username));
                LOG.info("终端用户登录完成");
                notifyClient(() -> callback.onSuccess(login));
            } catch (IOException | RuntimeException failure) {
                logFailure("终端用户登录", failure);
                notifyClient(() -> callback.onError("登录失败，请检查账号和部署地址"));
            }
        });
    }

    @Override public synchronized MeetingSession start(MeetingOptions options, MeetingListener listener) {
        requireOpen();
        if (current != null && !current.done) throw new IllegalStateException("已有会议正在进行");
        Session session = new Session(options, listener);
        current = session;
        enqueue(session::begin);
        return session;
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("会议客户端已关闭");
    }

    private void enqueue(Runnable task) {
        try { state.execute(task); }
        catch (RejectedExecutionException rejected) { if (!closed) throw rejected; }
    }

    private void notifyClient(Runnable task) {
        config.callbackExecutor().execute(() -> { if (!closed) task.run(); });
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        enqueue(() -> {
            if (current != null) current.cancel();
            io.shutdown();
            modelControl.shutdown();
            state.shutdown();
        });
    }

    private final class Session implements MeetingSession {
        private final MeetingOptions options;
        private final MeetingListener listener;
        private MeetingServerClient server;
        private MeetingClientConfig sessionConfig;
        private final MeetingDisplayState display = new MeetingDisplayState();
        private final FinalTranscriptCollector finals = new FinalTranscriptCollector();
        private volatile boolean done;
        private volatile boolean cancelled;
        // 状态队列关门后，callbackExecutor 中尚未派发的电平也不能再次点亮 UI。
        private volatile boolean audioLevelActive;
        private String meetingId;
        private Set<String> available = Set.of();
        private ModelSession model;
        private MeetingSessionState.Phase phase = MeetingSessionState.Phase.CREATING;
        private MeetingSessionState.Phase lastLoggedPhase;
        private boolean active;
        private boolean canStop;
        private boolean stopRequested;
        private boolean stopInputSent;
        private boolean modelFinished;
        private volatile boolean audioFailed;
        private boolean completionQueued;
        private boolean acceptingPresentation;
        private int sequence;
        private int savedEvents;
        private ScheduledFuture<?> deadline;
        // 仅唯一 io 队列访问。序号已由 state 分配，字幕与译文共用顺序；HTTP 失败
        // 也可能只是 ACK 丢失，因此保留同一不可变事件，不改号、不合并修订或跳过队首。
        // 后续事件／正常 Stop 才触发补交，真实成功后立即出队，不另加定时器或磁盘队列。
        private final ArrayDeque<PendingStandardEvent> pendingStandardEvents = new ArrayDeque<>();
        // 截图与文字事件独立：只有后端 ACK 后才推进序号。网络响应丢失时保留
        // 至多一个原包，下一次截图先以同一序号／offset／字节确认，不能换新图覆盖。
        // 这两个字段只由唯一 io 队列读写，不建立持久缓存或定时重试。
        private int presentationSequence;
        private PresentationFrame pendingPresentation;
        // 第一次正常收口时读取模型已经冻结的值，同场重试复用该快照。
        private OptionalLong frozenAudioDuration = OptionalLong.empty();
        private boolean usageRead;
        private boolean usageSubmitted;
        private boolean summarySubmitted;
        // 仅 io 队列访问；包括封口失败在内，同场收尾只读取一次录音事实。
        private boolean audioArtifactRead;
        private IOException audioArtifactFailure;
        private MeetingArtifactPublisher.Context publicationContext;
        private boolean publicationConfirmed;
        private Long genTime;
        private volatile MeetingResult readyResult;

        Session(MeetingOptions options, MeetingListener listener) {
            this.options = Objects.requireNonNull(options, "options");
            this.listener = Objects.requireNonNull(listener, "listener");
        }

        private void notifyListener(Runnable task) {
            notifyClient(() -> { if (!cancelled) task.run(); });
        }

        private void publish(String message) {
            if (phase != lastLoggedPhase) {
                LOG.info("会议状态 " + phase + "：" + message);
                lastLoggedPhase = phase;
            }
            var snapshot = new MeetingSessionState(phase, meetingId, available,
                    savedEvents, active, canStop, message);
            notifyListener(() -> listener.onState(snapshot));
        }

        private void showDisplay() {
            MeetingDisplaySnapshot snapshot = display.snapshot();
            notifyListener(() -> listener.onDisplay(snapshot));
        }

        private void begin() {
            if (cancelled || closed) return;
            if (options.retainAudio() && (!runtime.supportsRetainedAudio()
                    || !config.artifactPublisher().supportsAudioPublication())) {
                failStart("本场音频保留需要装配同源录音与产物发布");
                return;
            }
            publish("正在确认登录状态并创建会议…");
            io.execute(() -> {
                String step = "身份读取";
                try {
                    IdentityResult identity = readIdentity();
                    if (identity.status() != IdentityResult.Status.LOGGED_IN) {
                        LOG.info("会议开始身份未就绪 state=" + identity.status());
                        enqueue(() -> failStart(identity.message()));
                        return;
                    }
                    sessionConfig = config.forSession(identity.session());
                    server = servers.apply(sessionConfig);
                    LOG.info("本场身份已确认");
                    step = "能力发现";
                    var deployment = server.discover();
                    Set<String> setup = new HashSet<>(deployment.capabilities());
                    setup.retainAll(setupCapabilities());
                    requireCapabilities(setup);
                    LOG.info("本场部署能力已确认");
                    step = "创建会议";
                    CreatedMeeting created = server.createMeeting(options);
                    Set<String> granted = new HashSet<>();
                    for (String capability : options.requestedCapabilities()) {
                        if (setup.contains(capability) && created.capabilities().canUse(capability, setup))
                            granted.add(capability);
                    }
                    requireCapabilities(granted);
                    LOG.info("会议创建及必需能力已确认");
                    step = "模型材料/工厂选择";
                    ModelSessionGrant grant = runtime.usesServerModelMaterials()
                            ? server.requestModelSession(created.meetingId()) : null;
                    Set<String> local = new HashSet<>(runtime.usesServerModelMaterials()
                            ? adapters.realtimeCapabilities(grant.adapterId()) : runtime.localCapabilities());
                    // SummaryAdapter 是独立文本能力，不误当实时模型解析能力。
                    if (setupCapabilities().contains(MeetingCapabilityState.SUMMARY))
                        local.add(MeetingCapabilityState.SUMMARY);
                    Set<String> enabled = new HashSet<>();
                    for (String capability : options.requestedCapabilities()) {
                        if (local.contains(capability) && created.capabilities().canUse(capability, local))
                            enabled.add(capability);
                    }
                    requireCapabilities(enabled);
                    ModelAdapter adapter = grant == null ? null : adapters.create(grant.adapterId());
                    LOG.info("本场模型材料与工厂已准备");
                    step = "启动客户端输入";
                    server.startClient(created.meetingId());
                    LOG.info("后端客户端输入已启动");
                    enqueue(() -> startModel(created, grant, adapter, Set.copyOf(enabled)));
                } catch (IOException | RuntimeException failure) {
                    logFailure(step, failure);
                    enqueue(() -> failStart("创建或启动会议失败，请检查本场能力与模型配置"));
                }
            });
        }

        private void requireCapabilities(Set<String> capabilities) throws IOException {
            if (!capabilities.containsAll(options.requiredCapabilities())) {
                LOG.warning("本场缺少用户已选择的必需能力 " + options.requiredCapabilities());
                throw new IOException("本场缺少必需字幕或模式能力");
            }
        }

        private void startModel(CreatedMeeting created, ModelSessionGrant grant,
                ModelAdapter adapter, Set<String> enabled) {
            if (cancelled || closed) return;
            meetingId = created.meetingId();
            available = enabled;
            long startedAtMs = monotonicMs.getAsLong();
            display.start(startedAtMs, options.mode() == MeetingOptions.Mode.LIVE_TRANSLATION,
                    options.sourceLanguage(), options.targetLanguage());
            display.setWaitForModelLanguage(adapter != null && adapter.mayEmitDeferredSourceLanguage());
            try {
                // 续领闭包冻结原场Server（含身份）与ID，不能用当前账号重新开会。
                // HTTP由Runtime的续接工作线程执行，不进入state/modelControl/事件保存队列。
                MeetingServerClient frozenServer = server;
                String frozenMeetingId = created.meetingId();
                ModelSessionReconnector reconnector = grant == null ? null : () -> {
                    ModelSessionGrant next = frozenServer.requestModelSession(frozenMeetingId);
                    ModelAdapter fresh = adapters.create(next.adapterId());
                    LOG.fine("本场模型续接材料及新解析实例已准备");
                    return new ModelSessionConnection(next, fresh);
                };
                model = runtime.open(grant, adapter, event -> enqueue(() -> transcript(event)),
                        new ModelSessionListener() {
                            @Override public void onAudioInputMode(AudioInputMode mode) {
                                enqueue(() -> { if (!done) notifyListener(() -> listener.onAudioInputMode(mode)); });
                            }
                            @Override public void onAudioLevel(AudioInputLevel level) {
                                enqueue(() -> {
                                    if (!audioLevelActive) return;
                                    notifyListener(() -> {
                                        if (audioLevelActive) listener.onAudioLevel(level);
                                    });
                                });
                            }
                            @Override public void onAudioDirection(AudioDirectionSample sample) {
                                enqueue(() -> { if (!done) display.updateAudioDirection(sample); });
                            }
                            @Override public void onSegmentStarted(SegmentStartedEvent event) {
                                enqueue(() -> { if (!done) display.onSegmentStarted(event); });
                            }
                            @Override public void onTranslation(TranslationEvent event) {
                                enqueue(() -> translation(event));
                            }
                            @Override public void onFinished() { enqueue(Session.this::finished); }
                            @Override public void onAudioError(AudioInputErrorCode code, String message) {
                                enqueue(() -> {
                                    if (done) return;
                                    audioFailed = true;
                                    modelFinished = true;
                                    audioLevelActive = false;
                                    LOG.warning("本场音频输入终止 code=" + code);
                                    notifyListener(() -> listener.onAudioError(code, message));
                                    publish("音频输入已结束，请检查权限或音频连接后停止本场会议");
                                    maybeComplete();
                                });
                            }
                            @Override public void onError(String message) {
                                enqueue(() -> {
                                    if (done) return;
                                    modelFinished = true;
                                    audioLevelActive = false;
                                    LOG.warning("本场模型运行时终止");
                                    publish("模型会话已结束，请检查连接或停止本场会议");
                                    maybeComplete();
                                });
                            }
                        }, options.retainAudio(), reconnector);
                active = true;
                audioLevelActive = true;
                canStop = runtime.waitsForModelFinish();
                acceptingPresentation = options.presentationCapture();
                phase = MeetingSessionState.Phase.RUNNING;
                var info = new MeetingStarted(meetingId, startedAtMs, created.maxMeetingDurationMs(), available);
                notifyListener(() -> listener.onStarted(info));
                showDisplay();
                publish("会议已开始，正在接收标准字幕");
                LOG.info("会议已开始");
                modelControl.execute(() -> {
                    if (cancelled || closed || done) return;
                    try { model.start(); }
                    catch (RuntimeException failure) {
                        logFailure("启动模型运行时", failure);
                        enqueue(() -> failStart("打开模型会话失败"));
                    }
                });
            } catch (RuntimeException failure) {
                logFailure("创建模型会话", failure);
                failStart("打开模型会话失败");
            }
        }

        private void transcript(TranscriptEvent event) {
            if (done || modelFinished) return;
            TranscriptEvent standard = available.contains(MeetingCapabilityState.SPEAKER)
                    ? event : event.withoutSpeaker();
            long nowMs = monotonicMs.getAsLong();
            if (standard.text() != null && !standard.text().isBlank())
                notifyListener(() -> listener.onTranscriptActivity(nowMs));
            finals.apply(standard);
            display.apply(standard, nowMs);
            showDisplay();
            scheduleDeadline();
            int number = ++sequence;
            io.execute(() -> saveStandardEvent(new PendingStandardEvent(number, standard, null)));
        }

        private void translation(TranslationEvent event) {
            if (done || modelFinished || !available.contains(MeetingCapabilityState.TRANSLATION)) return;
            display.apply(event, monotonicMs.getAsLong());
            showDisplay();
            scheduleDeadline();
            int number = ++sequence;
            io.execute(() -> saveStandardEvent(new PendingStandardEvent(number, null, event)));
        }

        /** 先接纳本次原包，再确认全部队首；失败保留原顺序，不能先发送后来的修订。 */
        private void saveStandardEvent(PendingStandardEvent event) {
            if (closed || cancelled) return;
            pendingStandardEvents.addLast(event);
            try {
                savePendingStandardEvents();
            } catch (IOException failure) {
                logFailure("保存标准字幕或译文", failure);
                enqueue(() -> {
                    if (!done) publish("标准字幕或译文尚未确认，后续事件或停止时继续提交");
                });
            }
        }

        /**
         * 只能在 io 队列调用。Server 正常返回才代表 ACK，先出队再通知计数；若服务端
         * 已保存而响应丢失，下次仍以原 ID／序号／内容命中同一幂等事件，绝不分配新号。
         * complete 也复用此方法，未确认原包时不能进入用量、总结、发布或后端 Stop。
         */
        private void savePendingStandardEvents() throws IOException {
            while (!pendingStandardEvents.isEmpty()) {
                if (closed || cancelled) return;
                PendingStandardEvent event = pendingStandardEvents.getFirst();
                if (event.transcript() != null) {
                    server.appendTranscript(meetingId, event.sequence(), event.transcript());
                } else {
                    server.appendTranslation(meetingId, event.sequence(), event.translation());
                }
                pendingStandardEvents.removeFirst();
                LOG.fine("标准事件保存已确认 sequence=" + event.sequence());
                enqueue(() -> {
                    if (done) return;
                    if (event.transcript() != null) {
                        savedEvents++;
                        if (!runtime.waitsForModelFinish() && event.transcript().isFinal() && !stopRequested)
                            canStop = true;
                    }
                    publish(phase == MeetingSessionState.Phase.RUNNING
                            ? "标准字幕或译文已保存" : "正在等待本场正常收口");
                });
            }
        }

        private void scheduleDeadline() {
            cancelDeadline();
            var next = display.nextLanguageDeadlineMs();
            if (next.isPresent()) deadline = state.schedule(() -> {
                if (done) return;
                if (display.releaseExpiredLanguages(monotonicMs.getAsLong())) showDisplay();
                scheduleDeadline();
            }, Math.max(0, next.getAsLong() - monotonicMs.getAsLong()), TimeUnit.MILLISECONDS);
        }

        private void cancelDeadline() {
            if (deadline != null) { deadline.cancel(false); deadline = null; }
        }

        private void finished() {
            if (done || modelFinished) return;
            modelFinished = true;
            audioLevelActive = false;
            if (display.flushPendingLanguages()) showDisplay();
            cancelDeadline();
            publish(stopRequested ? "模型最终输出已接收，正在收口" : "模型已完成，可结束会议");
            maybeComplete();
        }

        @Override public void submitPresentation(long offsetMs, byte[] jpeg) {
            // 保留旧排队入口；只有 listener 提示，不把方法返回当保存确认。
            submitPresentation(offsetMs, jpeg, new Callback<>() {
                @Override public void onSuccess(Void ignored) { }
                @Override public void onError(String ignored) { }
            });
        }

        @Override public void submitPresentation(long offsetMs, byte[] jpeg, Callback<Void> callback) {
            Objects.requireNonNull(callback, "callback");
            PresentationFrame frame = new PresentationFrame(offsetMs, jpeg);
            enqueue(() -> {
                if (done || !acceptingPresentation) {
                    notifyListener(() -> callback.onError("本场当前不接收演示截图"));
                    return;
                }
                io.execute(() -> savePresentation(frame, callback));
            });
        }

        /** IO 队列内先检查新帧预算；不因本地超限保留图片或分配保存序号。 */
        private void savePresentation(PresentationFrame frame, Callback<Void> callback) {
            if (closed || cancelled) return;
            try {
                // 未确认包若成功重放，新帧才会用下一个序号；这里只计算候选值，
                // 不提前推进成功序号，也不覆盖 pendingPresentation。
                int candidate = presentationSequence + (pendingPresentation == null ? 1 : 2);
                HttpMeetingServerClient.validatePresentationRequestSize(
                        candidate, frame.offsetMs(), frame.jpeg());
            } catch (IOException invalidSize) {
                logFailure("检查演示截图大小", invalidSize);
                presentationFailure(callback, "演示截图超出1MiB传输预算，请缩小图片；字幕会议继续");
                return;
            }
            try {
                if (pendingPresentation != null) {
                    LOG.fine("重新确认原演示截图包 sequence=" + (presentationSequence + 1));
                    savePendingPresentation();
                }
                // 新图只有在旧包已经确认后才成为待确认包；旧包重放失败时丢弃
                // 本次新图，回调一次错误，下一次调用继续确认同一个旧包。
                pendingPresentation = frame;
                savePendingPresentation();
                enqueue(() -> notifyListener(() -> callback.onSuccess(null)));
            } catch (IOException | RuntimeException failure) {
                logFailure("保存演示截图", failure);
                presentationFailure(callback, "演示截图保存未确认；字幕会议继续");
            }
        }

        /** 同步 Server 端口正常返回表示真实 ACK；只在返回后推进，失败仍保留原帧。 */
        private void savePendingPresentation() throws IOException {
            int number = presentationSequence + 1;
            server.appendPresentation(meetingId, number,
                    pendingPresentation.offsetMs(), pendingPresentation.jpeg());
            presentationSequence = number;
            pendingPresentation = null;
            LOG.fine("演示截图保存已确认 sequence=" + number);
        }

        private void presentationFailure(Callback<Void> callback, String message) {
            // 图片是可选产物，失败独立通知 App，不进入标准文字事件的待确认队列，
            // 避免一次末帧失败阻断已经保存的文字会议正常停止。
            enqueue(() -> {
                if (!done) publish(message);
                notifyListener(() -> callback.onError(message));
            });
        }

        @Override public void swapDisplayColumns() {
            enqueue(() -> { if (!done && active && display.isBilingual()) { display.swapColumns(); showDisplay(); } });
        }

        @Override public void requestStop() {
            enqueue(() -> {
                if (done || !active || !canStop) return;
                stopRequested = true;
                audioLevelActive = false;
                canStop = false;
                phase = MeetingSessionState.Phase.STOPPING;
                if (display.flushPendingLanguages()) showDisplay();
                cancelDeadline();
                publish("正在等待模型最终字幕…");
                if (stopInputSent) { maybeComplete(); return; }
                AtomicBoolean continued = new AtomicBoolean();
                notifyListener(() -> listener.beforeStop(() -> {
                    if (continued.compareAndSet(false, true)) enqueue(this::continueStop);
                }));
            });
        }

        private void continueStop() {
            if (done || stopInputSent) return;
            acceptingPresentation = false;
            modelControl.execute(() -> {
                try {
                    model.stopInput();
                    enqueue(() -> { stopInputSent = true; maybeComplete(); });
                } catch (RuntimeException failure) {
                    logFailure("停止模型输入", failure);
                    enqueue(() -> { canStop = true; publish("停止模型输入失败，请再次确认停止"); });
                }
            });
        }

        private void maybeComplete() {
            if (done || completionQueued || !stopRequested || !stopInputSent
                    || runtime.waitsForModelFinish() && !modelFinished) return;
            completionQueued = true;
            phase = MeetingSessionState.Phase.FINALIZING;
            publish("正在保存本场最终结果…");
            SummaryInput input = null;
            if (!audioFailed && available.contains(MeetingCapabilityState.SUMMARY)
                    && options.summaryEnabled()) {
                try { input = finals.input(options.sourceLanguage()); }
                catch (IllegalArgumentException empty) {
                    // 静音或过短会议可以没有有效 final。没有总结材料不能把正常停止卡在
                    // STOP_PENDING：不请求模型、不伪造纪要，继续原冻结用量、录音发布和 Stop。
                    LOG.info("本场没有有效最终字幕，跳过纪要并继续正常停止");
                    publish("没有最终字幕，无法生成纪要；继续结束会议");
                }
            }
            SummaryInput summaryInput = input;
            io.execute(() -> complete(summaryInput));
        }

        private void complete(SummaryInput input) {
            if (cancelled || closed) return;
            String step = "检查标准事件保存";
            try {
                savePendingStandardEvents();
                if (cancelled || closed) return;
                step = "提交冻结采音用量";
                if (!usageRead) {
                    frozenAudioDuration = model.frozenAudioDurationMs();
                    usageRead = true;
                }
                if (frozenAudioDuration.isPresent() && !usageSubmitted) {
                    long durationMs = frozenAudioDuration.getAsLong();
                    LOG.info("冻结采音用量提交开始 durationMs=" + durationMs);
                    server.submitUsage(meetingId, durationMs);
                    usageSubmitted = true;
                    LOG.info("冻结采音用量已确认");
                }
                if (input != null && !summarySubmitted) {
                    step = "生成/提交标准纪要";
                    LOG.info("标准纪要生成开始");
                    SummarySessionGrant grant = runtime.usesServerModelMaterials()
                            ? server.requestSummarySession(meetingId) : null;
                    SummaryAdapter adapter = grant == null ? null : adapters.createSummary(grant.adapterId());
                    server.submitSummary(meetingId, runtime.summarize(grant, adapter, input));
                    summarySubmitted = true;
                    LOG.info("标准纪要已提交");
                }
                if (cancelled || closed) return;
                // 模型结束状态与同源录音封口是两件事。运行时须在 onFinished/onError 前
                // 停流并完成封口；只有本场 sealed 快照与冻结用量一致才可进入发布器。
                // 模型异常不抹掉已采录音；音频输入失败和显式取消仍不走正常发布。
                if (options.retainAudio() && !audioFailed && !publicationConfirmed) {
                    step = "发布本场封口音频";
                    readAudioArtifactOnce();
                    if (cancelled || closed) return;
                    if (!audioFailed) {
                        LOG.info("本场封口音频发布开始");
                        sessionConfig.artifactPublisher().publish(publicationContext);
                        publicationConfirmed = true;
                        LOG.info("本场封口音频发布已确认");
                    }
                }
                if (cancelled || closed) return;
                step = "后端 Stop";
                LOG.info("后端 Stop 开始");
                if (!server.stopMeeting(meetingId).accepted()) {
                    LOG.info("后端 Stop 尚未受理，等待用户再次确认");
                    enqueue(() -> completionFailed("后端仍在收口，请稍后再次确认停止"));
                    return;
                }
                LOG.info("后端 Stop 已受理，结果尚需确认");
                if (genTime == null) genTime = wallClockMs.getAsLong();
                model.close();
                if (audioFailed || options.resultOptions().policy()
                        == com.czur.aimeeting.client.result.ResultOptions.Policy.NO_ACTIVE_DELIVERY) {
                    enqueue(() -> endWithoutDelivery(audioFailed ? "音频输入已结束，本场已停止"
                            : "会议已结束；无痕互译不主动交付结果"));
                    return;
                }
                step = "读取实际 READY 清单";
                LOG.info("读取实际 READY 清单开始");
                // 收口只确认产物事实；正文／链接／通知由READY后的显式动作处理。
                MeetingResult finalResult = server.readResult(meetingId, genTime, false);
                enqueue(() -> {
                    if (done) return;
                    done = true;
                    active = false;
                    phase = MeetingSessionState.Phase.READY;
                    readyResult = finalResult;
                    notifyListener(() -> listener.onResult(finalResult));
                    publish("本场结果已就绪");
                    LOG.info("会议结果已就绪");
                });
            } catch (IOException | RuntimeException failure) {
                logFailure(step, failure);
                enqueue(() -> completionFailed("结束会议失败，请检查连接后再次确认停止"));
            }
        }

        private void readAudioArtifactOnce() throws IOException {
            if (!audioArtifactRead) {
                audioArtifactRead = true;
                try {
                    SealedAudioArtifact audio = model.sealedAudioArtifact()
                            .orElseThrow(() -> new IOException("本场录音尚未完成封口"));
                    if (!meetingId.equals(audio.meetingId()))
                        throw new IOException("录音产物不属于本场会议");
                    if (frozenAudioDuration.isEmpty()
                            || frozenAudioDuration.getAsLong() != audio.durationMs())
                        throw new IOException("录音事实与本场冻结采音用量不一致");
                    publicationContext = new MeetingArtifactPublisher.Context(meetingId, audio,
                            frozenAudioDuration.getAsLong(), sessionConfig);
                } catch (IOException | RuntimeException failure) {
                    audioArtifactFailure = new IOException("本场录音封口事实不可用", failure);
                }
            }
            if (audioArtifactFailure != null) throw audioArtifactFailure;
        }

        private void completionFailed(String message) {
            if (done) return;
            completionQueued = false;
            canStop = true;
            phase = MeetingSessionState.Phase.STOP_PENDING;
            publish(message);
        }

        private void endWithoutDelivery(String message) {
            if (done) return;
            done = true;
            active = false;
            phase = MeetingSessionState.Phase.ENDED;
            publish(message); // 只表达正常 Stop 受理，不冒称结果 READY。
            LOG.info("会议已停止，未主动交付结果");
        }

        private void failStart(String message) {
            if (done) return;
            audioLevelActive = false;
            done = true;
            active = false;
            canStop = false;
            phase = MeetingSessionState.Phase.FAILED;
            cancelDeadline();
            if (model != null) modelControl.execute(model::close);
            publish(message);
        }

        @Override public ResultActionAccess resultActionAccess() {
            return new ResultActionAccess() {
                @Override public void readContent(Callback<MeetingResultContent> callback) {
                    accessResult("读取结果内容", callback, result -> result.content() != null
                            ? result.content() : server.readResult(result.meetingId(), result.genTime(), true).content());
                }
                @Override public void sendEmail(java.util.List<String> recipients, String url,
                        Callback<Void> callback) {
                    var frozenRecipients = java.util.List.copyOf(recipients);
                    accessResult("发送结果邮件", callback, result -> {
                        server.sendResultEmail(result.meetingId(), frozenRecipients, url);
                        return null;
                    });
                }
            };
        }

        private <T> void accessResult(String step, Callback<T> callback, ResultCall<T> call) {
            Objects.requireNonNull(callback, "callback");
            enqueue(() -> {
                if (closed || cancelled) return;
                MeetingResult result = readyResult;
                if (result == null) {
                    notifyListener(() -> callback.onError("本场结果尚未就绪"));
                    return;
                }
                io.execute(() -> {
                    if (closed || cancelled) return;
                    try {
                        LOG.fine("同场结果访问开始 step=" + step);
                        T value = call.call(result);
                        notifyListener(() -> callback.onSuccess(value));
                    } catch (IOException | RuntimeException failure) {
                        logFailure(step, failure);
                        notifyListener(() -> callback.onError(step + "失败，请再次执行"));
                    }
                });
            });
        }

        @Override public void close() { enqueue(this::cancel); }

        private void cancel() {
            if (cancelled) return;
            cancelled = true;
            audioLevelActive = false;
            readyResult = null;
            done = true;
            active = false;
            cancelDeadline();
            if (model != null) modelControl.execute(model::close);
        }
    }

    /** 内部原包恰有一种标准事件；对象本身不可变，保留后不重新归一化或分配序号。 */
    private record PendingStandardEvent(int sequence, TranscriptEvent transcript,
            TranslationEvent translation) { }

    @FunctionalInterface
    private interface ResultCall<T> { T call(MeetingResult result) throws IOException; }

    private static void logFailure(String step, Throwable failure) {
        String code = "";
        String message = failure.getMessage();
        if (message != null) {
            var match = HTTP_CODE.matcher(message);
            if (match.find()) code = " HTTP=" + match.group(1);
        }
        // 只记本地阶段/异常类型/数字 HTTP 状态，不记录 Throwable、原文、URL 或凭据。
        LOG.warning("会议步骤失败 step=" + step + " type=" + failure.getClass().getSimpleName() + code);
    }

}
