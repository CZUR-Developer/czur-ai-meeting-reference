package com.czur.aimeeting.reference.meeting;

import static org.junit.Assert.*;
import com.czur.aimeeting.client.*;
import com.czur.aimeeting.client.result.*;
import com.czur.aimeeting.client.server.DeploymentOptions;
import com.czur.aimeeting.reference.*;
import com.czur.aimeeting.reference.ui.MeetingOverlayFactory;
import com.czur.aimeeting.reference.ui.OverlayViewFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Test;

/** 真实装配／冻结／插件执行，不使用Android空stub测试控件绘制。 */
public final class MeetingAppAssemblyTest {
    @After public void restoreDefault() {
        MeetingAppAssembly.clearPreparedMeeting();
        MeetingAppAssembly.registerResultActions(List.of(new WebResultActionPlugin()));
        MeetingAppAssembly.registerOverlayFactory(new OverlayViewFactory());
        MeetingAppAssembly.registerPageFactory(new MeetingPageViews.DefaultFactory());
        MeetingAppAssembly.configureResultWeb("");
    }

    @Test public void pageFactoryOnlyReplacesViewsAndKeepsDefaultWebAndWindow() {
        MeetingPageViews.Factory factory = activity -> { throw new AssertionError("尚未创建页面"); };
        MeetingAppAssembly.registerPageFactory(factory);
        assertSame(factory, MeetingAppAssembly.pageFactory());
        assertTrue(MeetingAppAssembly.overlayFactory() instanceof OverlayViewFactory);
        assertEquals(List.of("web"), MeetingAppAssembly.resultActions().stream().map(plugin -> plugin.id()).toList());
    }

    @Test public void processWebConfigurationDoesNotChangePreparedSameMeetingClosure() {
        AtomicReference<String> opened = new AtomicReference<>();
        MeetingAppAssembly.configureResultWeb("https://first.example.test/index.html");
        var prepared = MeetingAppAssembly.prepare(setup("web"), discovery(),
                new WebResultActionPlugin(MeetingAppAssembly.resultWebUrl(), opened::set)).meeting();
        MeetingAppAssembly.configureResultWeb("https://next.example.test/index.html");
        assertEquals("https://next.example.test/index.html", MeetingAppAssembly.resultWebUrl());
        MeetingAppAssembly.handoff(prepared);
        assertSame(prepared, MeetingAppAssembly.consumePreparedMeeting());
        assertEquals(ResultActionOutcome.Status.SUCCEEDED,
                execute(prepared, ready(), noProtocols(), Runnable::run).status());
        assertEquals("https://first.example.test/index.html?meetingId=mtg_same&genTime=555", opened.get());
    }

    @Test public void windowFactoryReplacesOnlyAssemblyWithoutCreatingMeetingOrWindow() {
        assertTrue(MeetingAppAssembly.overlayFactory() instanceof OverlayViewFactory);
        MeetingOverlayFactory factory = (context, open, swap, stop) -> { throw new AssertionError("尚未显示窗口"); };
        MeetingAppAssembly.registerOverlayFactory(factory);
        assertSame(factory, MeetingAppAssembly.overlayFactory());
        assertEquals(List.of("web"), MeetingAppAssembly.resultActions().stream().map(plugin -> plugin.id()).toList());
    }

    @Test public void defaultOnlyWebEvenWhenEmailServiceIsAvailable() {
        assertEquals(List.of("web"), MeetingAppAssembly.resultActions().stream().map(plugin -> plugin.id()).toList());
        assertEquals(List.of("web"), MeetingAppAssembly.resultRegistry().available(discovery()).stream()
                .map(plugin -> plugin.id()).toList());
        assertTrue(MeetingAppAssembly.resultRegistry().available(new MeetingDiscovery(
                new DeploymentOptions(Set.of(), 0, true), Set.of())).isEmpty());
    }

    @Test public void noticeReplacesAssemblyAndUsesSameReadyWithoutProtocols() {
        AtomicReference<String> shown = new AtomicReference<>();
        var notice = new CompletionNoticeAction(shown::set);
        MeetingAppAssembly.registerResultActions(List.of(notice));
        var prepared = MeetingStartConditions.prepare(setup -> MeetingStartCondition.Decision.allow(),
                setup("example.notice"), discovery(), notice);
        assertTrue(prepared.allowed());
        MeetingResult ready = ready();
        ResultActionOutcome outcome = execute(prepared.meeting(), ready, noProtocols(), Runnable::run);
        assertEquals(ResultActionOutcome.Status.SUCCEEDED, outcome.status());
        assertEquals("本场结果已就绪：mtg_same", shown.get());
        assertEquals(555, ready.genTime());
        assertNull(ready.content());
        assertEquals(MeetingResult.Status.READY, ready.status());
    }

    @Test public void hookFreezesOnceAndServiceConsumesSameObjectAfterPermissionWait() {
        AtomicInteger prepares = new AtomicInteger();
        AtomicReference<String> input = new AtomicReference<>("会前A");
        AtomicReference<String> received = new AtomicReference<>();
        AppResultActionPlugin plugin = new AppResultActionPlugin() {
            @Override public String id() { return "partner.notice"; }
            @Override public String displayName() { return "伙伴提示"; }
            @Override public Settings createSettings(android.content.Context ignored) { return new Settings(null, () -> this); }
            @Override public Preparation prepare(MeetingOptions options) {
                prepares.incrementAndGet();
                String frozen = input.get();
                return Preparation.allow((context, callback) -> {
                    received.set(frozen);
                    callback.onSuccess(ResultActionOutcome.success(id(), "完成"));
                });
            }
        };
        MeetingAppAssembly.registerResultActions(List.of(plugin));
        var prepared = MeetingStartConditions.prepare(setup -> MeetingStartCondition.Decision.allow(),
                setup(plugin.id()), discovery(), plugin).meeting();
        input.set("授权等待后B");
        MeetingAppAssembly.handoff(prepared);
        assertSame(prepared, MeetingAppAssembly.consumePreparedMeeting());
        assertNull(MeetingAppAssembly.consumePreparedMeeting());
        execute(prepared, ready(), noProtocols(), Runnable::run);
        assertEquals("会前A", received.get());
        assertEquals(1, prepares.get());
        MeetingAppAssembly.handoff(prepared);
        MeetingAppAssembly.clearPreparedMeeting();
        assertNull(MeetingAppAssembly.consumePreparedMeeting());
    }

    @Test public void webFrozenUrlMergesQueryAndFragmentAndWaitsForVisibleUi() {
        List<Runnable> pendingUi = new ArrayList<>();
        AtomicReference<String> opened = new AtomicReference<>();
        var web = new WebResultActionPlugin("https://result.example.test/index.html?x=1&meetingId=old&genTime=1#section",
                opened::set);
        var prepared = MeetingAppAssembly.prepare(setup("web"), discovery(), web).meeting();
        AtomicReference<ResultActionOutcome> outcome = new AtomicReference<>();
        var context = new ResultActionContext(ready(), noProtocols(), prepared.serviceCapabilities(),
                Runnable::run, command -> {
                    if (command instanceof AppResultActionPlugin.UiOperation) pendingUi.add(command);
                    else command.run();
                });
        MeetingAppAssembly.resultRegistry().execute(prepared.action(), context, callback(outcome));
        assertNull(outcome.get());
        assertNull(opened.get());
        assertEquals(1, pendingUi.size());
        // 模拟同进程Activity重新绑定；消费同一操作，不重新prepare或Stop。
        pendingUi.remove(0).run();
        assertEquals("https://result.example.test/index.html?x=1&meetingId=mtg_same&genTime=555#section", opened.get());
        assertEquals(ResultActionOutcome.Status.SUCCEEDED, outcome.get().status());
        assertEquals("https://result.example.test/?meetingId=a%2Fb&genTime=7#part",
                WebResultActionPlugin.resultUrl("https://result.example.test/#part", "a/b", 7));
    }

    @Test public void emailIsExplicitAndFailureCanRetrySameReadyWithFrozenRecipients() {
        List<String> recipients = new ArrayList<>(List.of("prepared@example.test"));
        var email = new EmailResultActionPlugin("https://result.example.test/?x=1#section", recipients);
        recipients.set(0, "changed@example.test");
        assertFalse(MeetingAppAssembly.prepare(setup("email"), discovery(), email).allowed());
        MeetingAppAssembly.registerResultActions(List.of(email));
        var prepared = MeetingAppAssembly.prepare(setup("email"), discovery(), email).meeting();
        AtomicInteger calls = new AtomicInteger();
        ResultActionAccess access = new ResultActionAccess() {
            @Override public void readContent(MeetingClient.Callback<MeetingResultContent> callback) { fail("邮件不读正文"); }
            @Override public void sendEmail(List<String> emails, String url, MeetingClient.Callback<Void> callback) {
                assertEquals(List.of("prepared@example.test"), emails);
                assertEquals("https://result.example.test/?x=1&meetingId=mtg_same&genTime=555#section", url);
                if (calls.incrementAndGet() == 1) callback.onError("受控普通失败");
                else callback.onSuccess(null);
            }
        };
        MeetingResult ready = ready();
        assertEquals(ResultActionOutcome.Status.FAILED, execute(prepared, ready, access, Runnable::run).status());
        assertEquals(1, calls.get());
        assertEquals(ResultActionOutcome.Status.SUCCEEDED, execute(prepared, ready, access, Runnable::run).status());
        assertEquals(2, calls.get());
        assertEquals(MeetingResult.Status.READY, ready.status());
        assertEquals(555, ready.genTime());
    }

    @Test public void optionalPdfContentFailureDoesNotChangeReady() {
        var pdf = new LocalPdfResultActionPlugin();
        MeetingAppAssembly.registerResultActions(List.of(pdf));
        var prepared = MeetingAppAssembly.prepare(setup(pdf.id()), discovery(), pdf).meeting();
        MeetingResult ready = ready();
        ResultActionAccess access = new ResultActionAccess() {
            @Override public void readContent(MeetingClient.Callback<MeetingResultContent> callback) {
                callback.onError("本场正文暂不可读");
            }
            @Override public void sendEmail(List<String> emails, String url, MeetingClient.Callback<Void> callback) { fail(); }
        };
        assertEquals(ResultActionOutcome.Status.FAILED, execute(prepared, ready, access, Runnable::run).status());
        assertEquals(MeetingResult.Status.READY, ready.status());
    }

    private static MeetingSetup setup(String id) {
        return new MeetingSetup(MeetingSetup.Mode.LIVE_TRANSCRIPT, "zh-CN", "en-US", false,
                ResultOptions.action(id), "", List.of(), false);
    }
    private static MeetingDiscovery discovery() {
        return new MeetingDiscovery(new DeploymentOptions(Set.of("transcript.realtime"), 600000, true), Set.of());
    }
    private static MeetingResult ready() {
        return new MeetingResult("mtg_same", "本场", 555, MeetingResult.Status.READY, Set.of("transcript.final"), null);
    }
    private static ResultActionAccess noProtocols() {
        return new ResultActionAccess() {
            @Override public void readContent(MeetingClient.Callback<MeetingResultContent> callback) { fail("动作不应读取正文"); }
            @Override public void sendEmail(List<String> emails, String url, MeetingClient.Callback<Void> callback) { fail("动作不应发送邮件"); }
        };
    }
    private static MeetingClient.Callback<ResultActionOutcome> callback(AtomicReference<ResultActionOutcome> outcome) {
        return new MeetingClient.Callback<>() {
            @Override public void onSuccess(ResultActionOutcome value) { outcome.set(value); }
            @Override public void onError(String message) { fail(message); }
        };
    }
    private static ResultActionOutcome execute(MeetingAppAssembly.PreparedMeeting prepared, MeetingResult ready,
            ResultActionAccess access, java.util.concurrent.Executor ui) {
        AtomicReference<ResultActionOutcome> outcome = new AtomicReference<>();
        MeetingAppAssembly.resultRegistry().execute(prepared.action(), new ResultActionContext(ready, access,
                prepared.serviceCapabilities(), Runnable::run, ui), callback(outcome));
        assertNotNull(outcome.get());
        return outcome.get();
    }
}
