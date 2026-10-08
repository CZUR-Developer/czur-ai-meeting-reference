package com.czur.aimeeting.client;

import static org.junit.Assert.*;

import com.czur.aimeeting.client.display.MeetingDisplaySnapshot;
import com.czur.aimeeting.client.provider.TingwuModelAdapter;
import com.czur.aimeeting.client.provider.BailianSummaryAdapter;
import com.czur.aimeeting.client.identity.IdentityProvider;
import com.czur.aimeeting.client.identity.IdentityResult;
import com.czur.aimeeting.client.identity.IdentitySession;
import com.czur.aimeeting.client.identity.ReferenceIdentityProvider;
import com.czur.aimeeting.client.server.*;
import com.czur.aimeeting.model.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** 只覆盖正常会议编排及明确能力门：同一核心使用真实材料端口或本地 Mock 端口。 */
public final class MeetingCoordinatorTest {
    private static final Set<String> ALL = Set.of(MeetingCapabilityState.TRANSCRIPT,
            MeetingCapabilityState.TRANSLATION, MeetingCapabilityState.SUMMARY, MeetingCapabilityState.SPEAKER);

    @Test public void missingIdentityIsVisibleAndNeverCreatesMeeting() throws Exception {
        try (Fixture f = new Fixture(false, Set.of(MeetingCapabilityState.TRANSCRIPT),
                new ReferenceIdentityProvider())) {
            BlockingQueue<IdentityResult> refreshed = new LinkedBlockingQueue<>();
            f.client.refreshIdentity(identityCallback(refreshed));
            assertEquals(IdentityResult.Status.LOGIN_REQUIRED, refreshed.poll(2, TimeUnit.SECONDS).status());
            f.session = f.client.start(options(MeetingOptions.Mode.LIVE_TRANSCRIPT,
                    MeetingOptions.ResultDelivery.WEB, false), f.probe);
            assertEquals("请先登录", f.probe.awaitPhase(MeetingSessionState.Phase.FAILED).message());
            assertTrue(f.server.steps.isEmpty());
            assertNull(f.sessionToken);
        }
    }

    @Test public void meetingReadsLatestIdentityAndKeepsItsCredentialSnapshot() throws Exception {
        ReferenceIdentityProvider identity = new ReferenceIdentityProvider();
        identity.updateSession(new IdentitySession("initial-fixture", "参考用户"));
        try (Fixture f = new Fixture(false, Set.of(MeetingCapabilityState.TRANSCRIPT), identity)) {
            BlockingQueue<IdentityResult> refreshed = new LinkedBlockingQueue<>();
            f.client.refreshIdentity(identityCallback(refreshed));
            assertEquals("initial-fixture", refreshed.poll(2, TimeUnit.SECONDS).session().accessToken());
            identity.updateSession(new IdentitySession("latest-fixture", "参考用户"));
            f.start(MeetingOptions.Mode.LIVE_TRANSCRIPT, MeetingOptions.ResultDelivery.WEB, false);
            assertEquals("latest-fixture", f.sessionToken);
            identity.clearSession();
            f.client.refreshIdentity(identityCallback(refreshed));
            assertEquals(IdentityResult.Status.LOGIN_REQUIRED, refreshed.poll(2, TimeUnit.SECONDS).status());
            assertEquals("latest-fixture", f.sessionToken);
            f.runtime.emit("s1", 1, "zh-CN", "最终字幕", true);
            f.probe.awaitCanStop();
            f.session.requestStop();
            assertNotNull(f.probe.result());
        }
    }

    @Test public void identityReadFailureReturnsTypedMessageWithoutStartingRuntime() throws Exception {
        try (Fixture f = new Fixture(false, Set.of(MeetingCapabilityState.TRANSCRIPT),
                () -> { throw new IOException("fixture-read-unavailable"); })) {
            BlockingQueue<IdentityResult> refreshed = new LinkedBlockingQueue<>();
            f.client.refreshIdentity(identityCallback(refreshed));
            IdentityResult identity = refreshed.poll(2, TimeUnit.SECONDS);
            assertEquals(IdentityResult.Status.READ_FAILED, identity.status());
            assertEquals("读取登录状态失败，请稍后重试", identity.message());
            f.session = f.client.start(options(MeetingOptions.Mode.LIVE_TRANSCRIPT,
                    MeetingOptions.ResultDelivery.WEB, false), f.probe);
            assertEquals(identity.message(), f.probe.awaitPhase(MeetingSessionState.Phase.FAILED).message());
            assertTrue(f.server.steps.isEmpty());
            assertEquals(1, f.runtime.started.getCount());
        }
    }

    private static MeetingClient.Callback<IdentityResult> identityCallback(BlockingQueue<IdentityResult> results) {
        return new MeetingClient.Callback<>() {
            @Override public void onSuccess(IdentityResult result) { results.add(result); }
            @Override public void onError(String message) { fail(message); }
        };
    }

    @Test public void mockMinutesSharesCoordinatorAndKeepsSummaryCapabilityAndFinalCaptureOrder() throws Exception {
        try (Fixture f = new Fixture(false, Set.of(MeetingCapabilityState.TRANSCRIPT, MeetingCapabilityState.SUMMARY))) {
            f.start(MeetingOptions.Mode.MEETING_MINUTES, MeetingOptions.ResultDelivery.LOCAL, true);
            f.runtime.emit("s1", 1, "zh-CN", "初稿", false);
            f.runtime.emit("s1", 2, "zh-CN", "最终字幕", true);
            f.probe.awaitCanStop();
            f.probe.beforeStop = continuation -> {
                byte[] jpeg = {1, 2};
                f.session.submitPresentation(1000, jpeg);
                jpeg[0] = 9;
                continuation.run();
            };
            f.session.requestStop();
            MeetingResult result = f.probe.result();
            assertNull(f.runtime.grant);
            assertNull(f.runtime.adapter);
            assertTrue(f.probe.info.availableCapabilities().contains(MeetingCapabilityState.SUMMARY));
            assertEquals("最终字幕", f.runtime.summaryInput.transcript());
            assertEquals(List.of("discover", "create", "start", "event1", "event2", "capture1", "summary", "stop", "manifest"), f.server.steps);
            assertNull(f.server.transcripts.get(0).sessionSpeakerId());
            assertArrayEquals(new byte[]{1, 2}, f.server.jpeg);
            assertEquals(MeetingResult.DeliveryStatus.LOCAL_READY, result.deliveryStatus());
            assertNotNull(result.content());
            assertTrue(f.server.includeContent);
            assertEquals(555L, result.genTime());
        }
    }

    @Test public void serverGrantChoosesFreshAdapterAndTailEventsAreSavedBeforeStop() throws Exception {
        try (Fixture f = new Fixture(true, Set.of(MeetingCapabilityState.TRANSCRIPT, MeetingCapabilityState.TRANSLATION))) {
            f.runtime.emitTail = true;
            f.runtime.duration = OptionalLong.of(0);
            f.start(MeetingOptions.Mode.LIVE_TRANSLATION, MeetingOptions.ResultDelivery.WEB, true);
            assertEquals(TingwuModelAdapter.ID, f.runtime.adapter.id());
            assertEquals("wss://model.example.test/signed", f.runtime.grant.endpoint());
            f.probe.beforeStop = continuation -> { f.session.submitPresentation(1500, new byte[]{3}); continuation.run(); };
            f.session.requestStop();
            MeetingResult result = f.probe.result();
            assertEquals(List.of("discover", "create", "grant", "start", "capture1", "event1", "translation2", "usage", "stop", "manifest"), f.server.steps);
            assertEquals(List.of(0L), f.server.usages);
            assertFalse(f.server.includeContent);
            assertEquals(MeetingResult.DeliveryStatus.WEB_AVAILABLE, result.deliveryStatus());
            assertTrue(result.resultUrl().contains("genTime=555"));
            assertEquals(1, f.runtime.stopCalls);
            assertEquals(1, f.probe.beforeStopCalls.get());
            assertTrue(f.probe.info.startedAtMs() > 0);
            assertEquals(600000L, f.probe.info.maxMeetingDurationMs());
        }
    }

    @Test public void translationSummaryWaitsForTailAndSavesBothBeforeStop() throws Exception {
        try (Fixture f = new Fixture(true, ALL)) {
            f.runtime.emitTail = true;
            f.runtime.duration = OptionalLong.of(1000);
            f.session = f.client.start(new MeetingOptions(MeetingOptions.Mode.LIVE_TRANSLATION,
                    "zh-CN", "en-US", false, MeetingOptions.ResultDelivery.WEB,
                    "https://result.example.test", List.of(), true), f.probe);
            assertTrue(f.runtime.started.await(2, TimeUnit.SECONDS));
            f.session.requestStop();
            assertNotNull(f.probe.result());
            assertTrue(f.probe.info.availableCapabilities().contains(MeetingCapabilityState.TRANSLATION));
            assertTrue(f.probe.info.availableCapabilities().contains(MeetingCapabilityState.SUMMARY));
            assertEquals("最后字幕", f.runtime.summaryInput.transcript());
            assertEquals(List.of("discover", "create", "grant", "start", "event1", "translation2",
                    "usage", "summaryGrant", "summary", "stop", "manifest"), f.server.steps);
            assertEquals(List.of(1000L), f.server.usages);
            assertTrue(f.server.requested.summaryEnabled());
        }
    }

    @Test public void checkedSummaryIsRequiredAlongsideTranslation() throws Exception {
        try (Fixture f = new Fixture(true, Set.of(MeetingCapabilityState.TRANSCRIPT,
                MeetingCapabilityState.TRANSLATION))) {
            f.session = f.client.start(new MeetingOptions(MeetingOptions.Mode.LIVE_TRANSLATION,
                    "zh-CN", "en-US", false, MeetingOptions.ResultDelivery.WEB,
                    "https://result.example.test", List.of(), true), f.probe);
            assertEquals(MeetingSessionState.Phase.FAILED,
                    f.probe.awaitPhase(MeetingSessionState.Phase.FAILED).phase());
            assertEquals(List.of("discover", "create"), f.server.steps);
            assertEquals(1, f.runtime.started.getCount());
        }
    }

    @Test public void blockedHttpDoesNotBlockActivityBeginDirectionDeadlineOrSnapshotSwap() throws Exception {
        try (Fixture f = new Fixture(true, Set.of(MeetingCapabilityState.TRANSCRIPT, MeetingCapabilityState.TRANSLATION))) {
            f.server.blockAppend = true;
            f.start(MeetingOptions.Mode.LIVE_TRANSLATION, MeetingOptions.ResultDelivery.WEB, false);
            f.runtime.listener.onAudioDirection(new AudioDirectionSample(90, 90, 0, AudioDirectionSample.Status.AVAILABLE));
            f.runtime.listener.onSegmentStarted(new SegmentStartedEvent("s1", 0));
            f.runtime.emit("s1", 1, "und", "Hello", false);
            assertTrue(f.server.appendEntered.await(1, TimeUnit.SECONDS));
            assertTrue(f.probe.activity.await(1, TimeUnit.SECONDS));
            MeetingDisplaySnapshot shown = f.probe.visible();
            assertEquals("und", shown.rows().get(0).transcript().language());
            assertEquals(MeetingDisplaySnapshot.Side.B, shown.rows().get(0).sourceSide());
            assertEquals("s1", shown.latestTranscriptSegmentId());
            assertEquals(1, f.server.releaseAppend.getCount());
            f.session.swapDisplayColumns();
            MeetingDisplaySnapshot swapped = f.probe.display();
            assertTrue(swapped.swapped());
            assertFalse(shown.swapped());
            f.runtime.emit("s2", 1, "und", "末尾快照", true);
            f.runtime.listener.onFinished();
            MeetingDisplaySnapshot flushed = f.probe.visibleWithRows(2);
            assertEquals("s2", flushed.latestTranscriptSegmentId());
            f.server.releaseAppend.countDown();
        }
    }

    @Test public void missingMandatoryTranscriptDoesNotRequestMaterialsOrOpenRuntime() throws Exception {
        try (Fixture f = new Fixture(true, Set.of(MeetingCapabilityState.TRANSLATION))) {
            f.session = f.client.start(options(MeetingOptions.Mode.LIVE_TRANSLATION, MeetingOptions.ResultDelivery.WEB, false), f.probe);
            assertEquals(MeetingSessionState.Phase.FAILED, f.probe.awaitPhase(MeetingSessionState.Phase.FAILED).phase());
            assertEquals(List.of("discover", "create"), f.server.steps);
            assertEquals(1, f.runtime.started.getCount());
            assertNull(f.probe.info);
        }
    }

    @Test public void stopPendingThenEmailFailureUsesOneAcceptedGenTimeAndSameLink() throws Exception {
        try (Fixture f = new Fixture(false, Set.of(MeetingCapabilityState.TRANSCRIPT))) {
            f.server.pendingStop = true;
            f.server.failEmail = true;
            f.runtime.duration = OptionalLong.of(1000);
            f.start(MeetingOptions.Mode.LIVE_TRANSCRIPT, MeetingOptions.ResultDelivery.EMAIL, false);
            f.runtime.emit("s1", 1, "zh-CN", "最终字幕", true);
            f.probe.awaitCanStop();
            f.session.requestStop();
            f.probe.awaitPhase(MeetingSessionState.Phase.STOP_PENDING);
            assertEquals(0, f.clockCalls.get());
            assertTrue(f.server.genTimes.isEmpty());
            f.runtime.duration = OptionalLong.of(2000); // 重试不重新读取／生成本场用量。
            f.session.requestStop();
            MeetingResult result = f.probe.result();
            assertEquals(MeetingResult.DeliveryStatus.EMAIL_FAILED, result.deliveryStatus());
            assertEquals(result.resultUrl(), f.server.emailUrl);
            assertTrue(result.resultUrl().endsWith("genTime=555#section"));
            assertEquals(List.of(555L), f.server.genTimes);
            assertEquals(1, f.clockCalls.get());
            assertEquals(1, f.runtime.stopCalls);
            assertEquals(1, f.probe.beforeStopCalls.get());
            assertEquals(1, f.runtime.durationReads);
            assertEquals(List.of(1000L), f.server.usages);
        }
    }

    @Test public void usageConfirmationRetryKeepsFrozenSnapshotBeforeSummaryAndStop() throws Exception {
        try (Fixture f = new Fixture(true, ALL)) {
            f.runtime.emitTail = true;
            f.runtime.duration = OptionalLong.of(1000);
            f.server.failUsageOnce = true;
            f.start(MeetingOptions.Mode.MEETING_MINUTES, MeetingOptions.ResultDelivery.WEB, false);
            f.session.requestStop();
            f.probe.awaitPhase(MeetingSessionState.Phase.STOP_PENDING);
            assertFalse(f.server.steps.contains("summary"));
            assertFalse(f.server.steps.contains("stop"));
            f.runtime.duration = OptionalLong.of(2000);
            f.session.requestStop();
            assertNotNull(f.probe.result());
            assertEquals(List.of("discover", "create", "grant", "start", "event1", "usage", "usage",
                    "summaryGrant", "summary", "stop", "manifest"), f.server.steps);
            assertEquals(List.of(1000L, 1000L), f.server.usages);
            assertEquals(1, f.runtime.durationReads);
            assertEquals(1, f.runtime.stopCalls);
        }
    }

    @Test public void tracelessEndsWithoutClaimingReadyOrActivelyDeliveringResults() throws Exception {
        try (Fixture f = new Fixture(false, Set.of(MeetingCapabilityState.TRANSCRIPT, MeetingCapabilityState.TRANSLATION))) {
            f.start(MeetingOptions.Mode.LIVE_TRANSLATION, MeetingOptions.ResultDelivery.TRACELESS, false);
            f.runtime.emit("s1", 1, "zh-CN", "最终字幕", true);
            f.probe.awaitCanStop();
            f.session.requestStop();
            assertFalse(f.probe.awaitPhase(MeetingSessionState.Phase.ENDED).active());
            assertFalse(f.server.steps.contains("manifest"));
            assertTrue(f.probe.results.isEmpty());
        }
    }

    /** 保留旧 App StopRule 覆盖：音频终止后用户正常停止，不等待已关闭模型的 FINISHED。 */
    @Test public void terminatedAudioCanStopWithoutModelFinishedCallback() throws Exception {
        try (Fixture f = new Fixture(true, Set.of(MeetingCapabilityState.TRANSCRIPT))) {
            f.runtime.emitFinished = false;
            f.start(MeetingOptions.Mode.LIVE_TRANSCRIPT, MeetingOptions.ResultDelivery.WEB, false);
            assertTrue(f.runtime.waitsForModelFinish());
            f.probe.awaitCanStop();
            f.runtime.listener.onAudioError(AudioInputErrorCode.PERMISSION_DENIED, "测试音频输入已终止");
            f.session.requestStop();
            MeetingSessionState ended = f.probe.awaitPhase(MeetingSessionState.Phase.ENDED);
            assertFalse(ended.active());
            assertFalse(ended.canStop());
            assertEquals(1, f.runtime.stopCalls);
            assertEquals(1, f.probe.beforeStopCalls.get());
            assertEquals(List.of("discover", "create", "grant", "start", "stop"), f.server.steps);
            assertFalse(f.server.steps.contains("manifest"));
            assertTrue(f.probe.results.isEmpty());
        }
    }

    private static MeetingOptions options(MeetingOptions.Mode mode, MeetingOptions.ResultDelivery delivery, boolean capture) {
        return new MeetingOptions(mode, "zh-CN", "en-US", capture, delivery,
                "https://result.example.test/index.html?existing=1#section", List.of("recipient@example.test"));
    }

    private static final class Fixture implements AutoCloseable {
        final Server server;
        final Runtime runtime;
        final Probe probe = new Probe();
        final AtomicInteger clockCalls = new AtomicInteger();
        final MeetingCoordinator client;
        volatile String sessionToken;
        MeetingSession session;
        Fixture(boolean real, Set<String> granted) {
            this(real, granted, new ReferenceIdentityProvider("test-token"));
        }
        Fixture(boolean real, Set<String> granted, IdentityProvider identity) {
            server = new Server(granted);
            runtime = new Runtime(real);
            var config = new MeetingClientConfig("http://127.0.0.1", identity,
                    ClientIdentity.reference(), MeetingProfile.reference(), Runnable::run);
            client = new MeetingCoordinator(config,
                    new ModelAdapterRegistry(), runtime, resolved -> { sessionToken = resolved.accessToken(); return server; },
                    () -> System.nanoTime() / 1_000_000L, () -> { clockCalls.incrementAndGet(); return 555; });
        }
        void start(MeetingOptions.Mode mode, MeetingOptions.ResultDelivery delivery, boolean capture) throws Exception {
            session = client.start(options(mode, delivery, capture), probe);
            assertTrue(runtime.started.await(2, TimeUnit.SECONDS));
        }
        @Override public void close() { server.releaseAppend.countDown(); client.close(); }
    }

    private static final class Probe implements MeetingListener {
        final BlockingQueue<MeetingSessionState> states = new LinkedBlockingQueue<>();
        final BlockingQueue<MeetingDisplaySnapshot> displays = new LinkedBlockingQueue<>();
        final BlockingQueue<MeetingResult> results = new LinkedBlockingQueue<>();
        final CountDownLatch activity = new CountDownLatch(1);
        final AtomicInteger beforeStopCalls = new AtomicInteger();
        java.util.function.Consumer<Runnable> beforeStop = Runnable::run;
        volatile MeetingStarted info;
        @Override public void onStarted(MeetingStarted started) { info = started; }
        @Override public void onState(MeetingSessionState state) { states.add(state); }
        @Override public void onDisplay(MeetingDisplaySnapshot display) { displays.add(display); }
        @Override public void onTranscriptActivity(long nowMs) { activity.countDown(); }
        @Override public void beforeStop(Runnable continuation) { beforeStopCalls.incrementAndGet(); beforeStop.accept(continuation); }
        @Override public void onResult(MeetingResult result) { results.add(result); }
        MeetingSessionState awaitPhase(MeetingSessionState.Phase phase) throws Exception {
            MeetingSessionState next;
            do { next = states.poll(3, TimeUnit.SECONDS); assertNotNull("应收到会议状态", next); } while (next.phase() != phase);
            return next;
        }
        void awaitCanStop() throws Exception {
            MeetingSessionState next;
            do { next = states.poll(3, TimeUnit.SECONDS); assertNotNull("最终事件保存后可停止", next); } while (!next.canStop());
        }
        MeetingDisplaySnapshot display() throws Exception {
            var value = displays.poll(3, TimeUnit.SECONDS); assertNotNull("应收到显示快照", value); return value;
        }
        MeetingDisplaySnapshot visible() throws Exception { return visibleWithRows(1); }
        MeetingDisplaySnapshot visibleWithRows(int count) throws Exception {
            MeetingDisplaySnapshot value;
            do { value = display(); } while (value.rows().size() < count);
            return value;
        }
        MeetingResult result() throws Exception {
            var result = results.poll(3, TimeUnit.SECONDS); assertNotNull("应收到实际就绪结果", result); return result;
        }
    }

    private static final class Runtime implements MeetingRuntimePort {
        final boolean real;
        final CountDownLatch started = new CountDownLatch(1);
        TranscriptListener transcripts;
        ModelSessionListener listener;
        ModelSessionGrant grant;
        ModelAdapter adapter;
        SummaryInput summaryInput;
        volatile int stopCalls;
        boolean emitTail;
        boolean emitFinished = true;
        volatile OptionalLong duration = OptionalLong.empty();
        volatile int durationReads;
        Runtime(boolean real) { this.real = real; }
        @Override public boolean usesServerModelMaterials() { return real; }
        @Override public Set<String> localCapabilities() { return ALL; }
        @Override public ModelSession open(ModelSessionGrant grant, ModelAdapter adapter, TranscriptListener transcripts, ModelSessionListener listener) {
            this.grant = grant; this.adapter = adapter; this.transcripts = transcripts; this.listener = listener;
            return new ModelSession() {
                @Override public void start() { started.countDown(); }
                @Override public void stopInput() {
                    stopCalls++;
                    if (real) {
                        if (emitTail) {
                            emit("tail", 1, "zh-CN", "最后字幕", true);
                            listener.onTranslation(new TranslationEvent("t1", "tail", 1, "zh-CN", "en-US", "Final text", true));
                        }
                        if (emitFinished) listener.onFinished();
                    }
                }
                @Override public void close() { }
                @Override public OptionalLong frozenAudioDurationMs() {
                    ++durationReads;
                    assertTrue("协调器必须在停止输入后读取用量", stopCalls > 0);
                    return duration;
                }
            };
        }
        void emit(String id, int revision, String language, String text, boolean end) {
            transcripts.onTranscript(new TranscriptEvent(id, revision, 0, 100, language, text, end, "speaker-1"));
        }
        @Override public SummaryResult summarize(SummarySessionGrant grant, SummaryAdapter adapter, SummaryInput input) {
            summaryInput = input;
            return new SummaryResult(input.language(), "总结", "概览", List.of("要点"), List.of());
        }
    }

    private static final class Server implements MeetingServerClient {
        final Set<String> granted;
        final List<String> steps = Collections.synchronizedList(new ArrayList<>());
        final List<TranscriptEvent> transcripts = Collections.synchronizedList(new ArrayList<>());
        final List<Long> genTimes = Collections.synchronizedList(new ArrayList<>());
        final List<Long> usages = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch appendEntered = new CountDownLatch(1);
        final CountDownLatch releaseAppend = new CountDownLatch(1);
        volatile boolean blockAppend;
        boolean pendingStop;
        boolean failEmail;
        boolean failUsageOnce;
        boolean includeContent;
        byte[] jpeg;
        String emailUrl;
        MeetingOptions requested;
        Server(Set<String> granted) { this.granted = granted; }
        @Override public DeploymentOptions discover() { steps.add("discover"); return new DeploymentOptions(ALL, 86400000L, true); }
        @Override public CreatedMeeting createMeeting(MeetingOptions options) throws IOException {
            requested = options;
            steps.add("create");
            try {
                JSONArray decisions = new JSONArray();
                for (String capability : granted) decisions.put(new JSONObject().put("capability", capability).put("availability", "AVAILABLE"));
                return new CreatedMeeting("mtg_test", null, MeetingCapabilityState.fromCreateResponse(new JSONObject().put("capabilityDecisions", decisions)), 600000L);
            } catch (Exception failure) { throw new IOException(failure); }
        }
        @Override public ModelSessionGrant requestModelSession(String id) { steps.add("grant"); return new ModelSessionGrant(id, TingwuModelAdapter.ID, "wss://model.example.test/signed", "2030-01-01T00:00:00Z", null, Map.of()); }
        @Override public SummarySessionGrant requestSummarySession(String id) {
            steps.add("summaryGrant");
            return new SummarySessionGrant(BailianSummaryAdapter.ID, "https://summary.example.test",
                    "2030-01-01T00:00:00Z", "fixture-summary");
        }
        @Override public void startClient(String id) { steps.add("start"); }
        @Override public void appendTranscript(String id, int number, TranscriptEvent event) throws IOException {
            steps.add("event" + number); transcripts.add(event); appendEntered.countDown();
            if (blockAppend) try { if (!releaseAppend.await(4, TimeUnit.SECONDS)) throw new IOException("测试未释放 HTTP"); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException(interrupted); }
        }
        @Override public void appendTranslation(String id, int number, TranslationEvent event) { steps.add("translation" + number); }
        @Override public void appendPresentation(String id, int number, long offset, byte[] jpeg) { steps.add("capture" + number); this.jpeg = jpeg; }
        @Override public void submitSummary(String id, SummaryResult summary) { steps.add("summary"); }
        @Override public void submitUsage(String id, long duration) throws IOException {
            assertEquals("mtg_test", id);
            steps.add("usage"); usages.add(duration);
            if (failUsageOnce) { failUsageOnce = false; throw new IOException("受控用量响应未确认"); }
        }
        @Override public StopMeetingResponse stopMeeting(String id) {
            steps.add("stop");
            boolean accepted = !pendingStop; pendingStop = false;
            return new StopMeetingResponse(accepted, accepted ? "INPUT_ACCEPTED" : "INPUT_COMPLETE_PENDING", accepted ? "receipt" : null, "GENERATING");
        }
        @Override public MeetingResult readResult(String id, long genTime, boolean includeContent) {
            steps.add("manifest"); genTimes.add(genTime); this.includeContent = includeContent;
            return new MeetingResult(id, "本场", genTime, MeetingResult.Status.READY, Set.of("transcript.final"),
                    includeContent ? new MeetingResultContent(Set.of("transcript.final"), "最终字幕", List.of(), List.of(), null, List.of()) : null);
        }
        @Override public void sendResultEmail(String id, List<String> recipients, String url) throws IOException {
            steps.add("email"); emailUrl = url; if (failEmail) throw new IOException("测试邮箱未接通");
        }
    }
}
