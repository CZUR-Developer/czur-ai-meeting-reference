package com.czur.aimeeting.client.result;

import static org.junit.Assert.*;
import com.czur.aimeeting.client.MeetingClient;
import com.czur.aimeeting.client.MeetingDiscovery;
import com.czur.aimeeting.client.MeetingOptions;
import com.czur.aimeeting.client.MeetingResult;
import com.czur.aimeeting.client.MeetingResultContent;
import com.czur.aimeeting.client.server.DeploymentOptions;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** 普通插件扩展、会前冻结、真实产物门槛与同一READY手动执行；不建立异常平台。 */
public final class ResultActionRegistryTest {
    @Test public void onlyRegisteredImplementationsAppearRegardlessOfDeclaredEmailService() {
        ResultActionPlugin notice = plugin("example.notice", options ->
                ResultActionPlugin.Preparation.allow((context, callback) ->
                        callback.onSuccess(ResultActionOutcome.success("example.notice", "已完成"))));
        var registry = new ResultActionRegistry(List.of(notice));
        assertEquals(List.of(notice), registry.available(discovery(true)));
        assertFalse(registry.prepare("email", options(), discovery(true)).allowed());
        assertTrue(new ResultActionRegistry(List.of()).available(discovery(true)).isEmpty());
    }

    @Test public void prepareReadsLocalConditionAndFreezesParametersOnce() throws Exception {
        AtomicReference<String> preparedRecipient = new AtomicReference<>();
        AtomicReference<String> received = new AtomicReference<>();
        AtomicInteger prepares = new AtomicInteger();
        var plugin = plugin("partner.notice", options -> {
            prepares.incrementAndGet();
            String snapshot = preparedRecipient.get();
            if (snapshot == null) return ResultActionPlugin.Preparation.block("请先完成接收者登记");
            return ResultActionPlugin.Preparation.allow((context, callback) -> {
                received.set(snapshot + ":" + context.result().meetingId() + ":" + context.result().genTime());
                callback.onSuccess(ResultActionOutcome.success("partner.notice", "已完成"));
            });
        });
        var registry = new ResultActionRegistry(List.of(plugin));
        assertEquals("请先完成接收者登记", registry.prepare("partner.notice", options(), discovery(false)).message());
        preparedRecipient.set("准备对象A");
        var prepared = registry.prepare("partner.notice", options(), discovery(false));
        preparedRecipient.set("稍后变化B");
        assertEquals(ResultActionOutcome.Status.SUCCEEDED, execute(registry, prepared.action(), context(Set.of())).status());
        assertEquals("准备对象A:mtg_ready:555", received.get());
        assertEquals(2, prepares.get()); // 一次阻止、一场允许；会后没有重新准备。
    }

    @Test public void failedActionCanBeManuallyExecutedAgainWithSameReadyFacts() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var plugin = plugin("example.notice", options -> ResultActionPlugin.Preparation.allow((context, callback) -> {
            if (calls.incrementAndGet() == 1) callback.onError("本次提示未显示，请再次执行");
            else callback.onSuccess(ResultActionOutcome.success("example.notice", "已完成"));
        }));
        var registry = new ResultActionRegistry(List.of(plugin));
        var prepared = registry.prepare("example.notice", options(), discovery(false)).action();
        var context = context(Set.of("transcript.final"));
        assertEquals(ResultActionOutcome.Status.FAILED, execute(registry, prepared, context).status());
        assertEquals(1, calls.get()); // 没有自动重试。
        assertEquals(ResultActionOutcome.Status.SUCCEEDED, execute(registry, prepared, context).status());
        assertEquals(MeetingResult.Status.READY, context.result().status());
        assertEquals(555, context.result().genTime());
        assertEquals(2, calls.get());
    }

    @Test public void requiredServiceAndActualArtifactMustExistBeforeExecution() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ResultActionPlugin plugin = new ResultActionPlugin() {
            @Override public String id() { return "partner.summary"; }
            @Override public String displayName() { return "总结通知"; }
            @Override public Set<String> requiredArtifactTypes() { return Set.of("summary.structured"); }
            @Override public Set<String> requiredServiceCapabilities() { return Set.of(ResultActionRegistry.RESULT_EMAIL); }
            @Override public Preparation prepare(MeetingOptions options) {
                return Preparation.allow((context, callback) -> {
                    calls.incrementAndGet(); callback.onSuccess(ResultActionOutcome.success(id(), "已完成"));
                });
            }
        };
        var registry = new ResultActionRegistry(List.of(plugin));
        assertTrue(registry.available(discovery(false)).isEmpty());
        assertFalse(registry.prepare(plugin.id(), options(), discovery(false)).allowed());
        var prepared = registry.prepare(plugin.id(), options(), discovery(true)).action();
        assertEquals(ResultActionOutcome.Status.FAILED, execute(registry, prepared, context(Set.of("transcript.final"))).status());
        assertEquals(0, calls.get());
        assertEquals(ResultActionOutcome.Status.SUCCEEDED, execute(registry, prepared, context(Set.of("summary.structured"))).status());
    }

    @Test public void oldConstructorsMapToActionsWithoutChangingSummaryOrSavingPolicy() {
        String[] ids = {"web", "local.pdf", "email", ""};
        int index = 0;
        for (MeetingOptions.ResultDelivery delivery : MeetingOptions.ResultDelivery.values()) {
            var old = new MeetingOptions(MeetingOptions.Mode.MEETING_MINUTES, "zh-CN", "en-US", false,
                    delivery, "https://result.example.test", List.of());
            assertEquals(ids[index++], old.resultOptions().actionId());
            assertEquals(delivery, old.resultOptions().legacyDelivery());
            assertTrue(old.summaryEnabled());
            assertEquals(delivery == MeetingOptions.ResultDelivery.TRACELESS
                    ? ResultOptions.Policy.NO_ACTIVE_DELIVERY : ResultOptions.Policy.READY, old.resultOptions().policy());
        }
        var chosen = new MeetingOptions(MeetingOptions.Mode.LIVE_TRANSLATION, "zh-CN", "en-US", false,
                ResultOptions.action("partner.notice"), "", List.of(), true);
        assertEquals("partner.notice", chosen.resultOptions().actionId());
        assertTrue(chosen.summaryEnabled());
        assertTrue(chosen.requiredCapabilities().contains("translation.realtime"));
        assertTrue(chosen.requiredCapabilities().contains("summary.structured"));
    }

    private static ResultActionPlugin plugin(String id,
            java.util.function.Function<MeetingOptions, ResultActionPlugin.Preparation> prepare) {
        return new ResultActionPlugin() {
            @Override public String id() { return id; }
            @Override public String displayName() { return "本场完成提示"; }
            @Override public Preparation prepare(MeetingOptions options) { return prepare.apply(options); }
        };
    }
    private static MeetingOptions options() {
        return new MeetingOptions(MeetingOptions.Mode.LIVE_TRANSCRIPT, "zh-CN", "en-US", false,
                MeetingOptions.ResultDelivery.WEB, "https://result.example.test", List.of());
    }
    private static MeetingDiscovery discovery(boolean email) {
        return new MeetingDiscovery(new DeploymentOptions(Set.of("transcript.realtime"), 600000, email), Set.of());
    }
    private static ResultActionContext context(Set<String> artifacts) {
        return new ResultActionContext(new MeetingResult("mtg_ready", "本场", 555,
                MeetingResult.Status.READY, artifacts, null), new ResultActionAccess() {
                    @Override public void readContent(MeetingClient.Callback<MeetingResultContent> callback) {
                        fail("提示插件不能读取正文");
                    }
                    @Override public void sendEmail(List<String> recipients, String url, MeetingClient.Callback<Void> callback) {
                        fail("提示插件不能调用邮件协议");
                    }
                }, ResultActionRegistry.serviceCapabilities(discovery(true)), Runnable::run, Runnable::run);
    }
    private static ResultActionOutcome execute(ResultActionRegistry registry,
            ResultActionRegistry.PreparedAction prepared, ResultActionContext context) throws Exception {
        CompletableFuture<ResultActionOutcome> outcome = new CompletableFuture<>();
        registry.execute(prepared, context, new MeetingClient.Callback<>() {
            @Override public void onSuccess(ResultActionOutcome value) { outcome.complete(value); }
            @Override public void onError(String message) { fail(message); }
        });
        return outcome.get(2, TimeUnit.SECONDS);
    }
}
