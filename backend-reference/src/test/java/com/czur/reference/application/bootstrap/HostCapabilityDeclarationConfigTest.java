package com.czur.reference.application.bootstrap;

import com.czur.reference.application.persistence.jdbc.DatabaseRuntimeConfig;
import com.czur.reference.domain.capability.CapabilityDecision;
import com.czur.reference.domain.capability.CapabilityIds;
import com.czur.reference.domain.capability.CapabilityPlanner;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 宿主在代码里声明能力时的公共配置门禁：不启动数据库、网络或真实模型。
 *
 * <p>覆盖四点：传入声明后环境变量里的同名四项一律不生效；不一致时只输出
 * 一条不含清单内容的告警；未传入声明时旧行为不变；{@code summary.server}
 * 在声明后需要执行映射并能被 Create 决策授予。</p>
 */
class HostCapabilityDeclarationConfigTest {
    private static final String SECRET = "page-secret-0123456789-abcdefghijkl";

    @Test
    void hostDeclarationReplacesEnvironmentCapabilityLists() {
        Map<String, String> values = valid();
        // 环境变量刻意给出与代码声明完全不同的清单；传入声明后不得生效。
        values.put(MeetingDeploymentConfig.PROFILE_OPTIONAL_CAPABILITIES,
                CapabilityIds.SPEAKER_DIARIZATION_SESSION);
        values.put(MeetingDeploymentConfig.ENTITLEMENT_SUPPORTED_CAPABILITIES,
                CapabilityIds.TRANSCRIPT_REALTIME + "," + CapabilityIds.SPEAKER_DIARIZATION_SESSION);
        values.put(MeetingDeploymentConfig.MODEL_CAPABILITIES,
                CapabilityIds.TRANSCRIPT_REALTIME + "," + CapabilityIds.SPEAKER_DIARIZATION_SESSION);
        var config = config(values, declaration());

        assertEquals(Set.of(CapabilityIds.TRANSCRIPT_REALTIME),
                config.profile().requiredCapabilities());
        assertEquals(Set.of(CapabilityIds.TRANSLATION_REALTIME,
                        CapabilityIds.SUMMARY_STRUCTURED, CapabilityIds.SUMMARY_SERVER_GENERATED,
                        CapabilityIds.SPEAKER_DIARIZATION_SESSION),
                config.profile().optionalCapabilities());
        assertEquals(Set.of(CapabilityIds.TRANSCRIPT_REALTIME,
                        CapabilityIds.TRANSLATION_REALTIME, CapabilityIds.SUMMARY_STRUCTURED,
                        CapabilityIds.SUMMARY_SERVER_GENERATED,
                        CapabilityIds.SPEAKER_DIARIZATION_SESSION),
                config.entitlement().supportedCapabilities());
        assertEquals(Set.of(CapabilityIds.TRANSCRIPT_REALTIME,
                        CapabilityIds.TRANSLATION_REALTIME, CapabilityIds.SUMMARY_STRUCTURED,
                        CapabilityIds.SUMMARY_SERVER_GENERATED,
                        CapabilityIds.SPEAKER_DIARIZATION_SESSION),
                config.mockCapabilities().capabilities());
        // 环境变量声明的摘要能力不应因为被忽略而残留执行映射缺口。
        assertEquals(List.of("model.summary.server"),
                config.profile().affectedWorkflowsOf(CapabilityIds.SUMMARY_SERVER_GENERATED));
        assertEquals(List.of("summary.structured"),
                config.profile().producedArtifactsOf(CapabilityIds.SUMMARY_SERVER_GENERATED));
    }

    @Test
    void hostDeclarationDoesNotRequireAnyCapabilityEnvironmentKeys() {
        Map<String, String> values = valid();
        values.remove(MeetingDeploymentConfig.PROFILE_REQUIRED_CAPABILITIES);
        values.remove(MeetingDeploymentConfig.PROFILE_OPTIONAL_CAPABILITIES);
        values.remove(MeetingDeploymentConfig.ENTITLEMENT_SUPPORTED_CAPABILITIES);
        values.remove(MeetingDeploymentConfig.MOCK_CAPABILITIES);
        values.remove(MeetingDeploymentConfig.MODEL_CAPABILITIES);
        var config = config(values, declaration());
        assertEquals(Set.of(CapabilityIds.TRANSCRIPT_REALTIME),
                config.profile().requiredCapabilities());
        assertTrue(config.entitlement().supportedCapabilities()
                .contains(CapabilityIds.SUMMARY_SERVER_GENERATED));
    }

    @Test
    void environmentOnlyPathKeepsLegacyCapabilityBehaviour() {
        Map<String, String> values = valid();
        values.put(MeetingDeploymentConfig.PROFILE_OPTIONAL_CAPABILITIES,
                CapabilityIds.TRANSLATION_REALTIME);
        values.put(MeetingDeploymentConfig.ENTITLEMENT_SUPPORTED_CAPABILITIES,
                CapabilityIds.TRANSCRIPT_REALTIME + "," + CapabilityIds.TRANSLATION_REALTIME);
        values.put(MeetingDeploymentConfig.MODEL_CAPABILITIES,
                CapabilityIds.TRANSCRIPT_REALTIME + "," + CapabilityIds.TRANSLATION_REALTIME);
        var config = MeetingDeploymentConfig.fromEnvironmentWithExternalIdentity(values,
                "trusted-audience", "trusted-client");
        // 旧签名仍按环境变量读取，并保留“本场发言人始终可请求”的基线。
        assertEquals(Set.of(CapabilityIds.TRANSLATION_REALTIME,
                        CapabilityIds.SPEAKER_DIARIZATION_SESSION),
                config.profile().optionalCapabilities());
        assertEquals(Set.of(CapabilityIds.TRANSCRIPT_REALTIME,
                        CapabilityIds.TRANSLATION_REALTIME),
                config.entitlement().supportedCapabilities());

        // 旧行为下必选能力键缺失仍然是配置错误。
        Map<String, String> broken = valid();
        broken.remove(MeetingDeploymentConfig.PROFILE_REQUIRED_CAPABILITIES);
        assertThrows(IllegalArgumentException.class,
                () -> MeetingDeploymentConfig.fromEnvironmentWithExternalIdentity(broken,
                        "trusted-audience", "trusted-client"));
    }

    @Test
    void environmentCapabilityDriftLogsOneWarningPerKeyWithoutResourceLists() {
        Map<String, String> values = valid();
        values.put(MeetingDeploymentConfig.PROFILE_OPTIONAL_CAPABILITIES,
                CapabilityIds.SPEAKER_DIARIZATION_SESSION);
        values.put(MeetingDeploymentConfig.ENTITLEMENT_SUPPORTED_CAPABILITIES,
                CapabilityIds.TRANSCRIPT_REALTIME + "," + CapabilityIds.SPEAKER_DIARIZATION_SESSION);
        values.put(MeetingDeploymentConfig.MODEL_CAPABILITIES,
                CapabilityIds.TRANSCRIPT_REALTIME + "," + CapabilityIds.SPEAKER_DIARIZATION_SESSION);
        List<String> warnings = warningsOf(() -> config(values, declaration()));
        assertEquals(3, warnings.size(), "只应为差异键输出告警：" + warnings);
        String optionalWarning = warnings.stream()
                .filter(message -> message.contains(
                        MeetingDeploymentConfig.PROFILE_OPTIONAL_CAPABILITIES))
                .findFirst().orElseThrow();
        // 差异项数：翻译、结构化纪要、服务端纪要三项；发言人两侧一致。
        assertTrue(optionalWarning.contains("3"), optionalWarning);
        for (String warning : warnings) {
            assertFalse(warning.contains(CapabilityIds.TRANSLATION_REALTIME), warning);
            assertFalse(warning.contains(CapabilityIds.SUMMARY_STRUCTURED), warning);
            assertFalse(warning.contains(CapabilityIds.SUMMARY_SERVER_GENERATED), warning);
        }
    }

    @Test
    void matchingEnvironmentCapabilityKeysStaySilent() {
        Map<String, String> values = valid();
        values.put(MeetingDeploymentConfig.PROFILE_OPTIONAL_CAPABILITIES,
                String.join(",", declaration().optionalCapabilities()));
        values.put(MeetingDeploymentConfig.ENTITLEMENT_SUPPORTED_CAPABILITIES,
                String.join(",", declaration().entitlementSupportedCapabilities()));
        values.put(MeetingDeploymentConfig.MODEL_CAPABILITIES,
                String.join(",", declaration().modelCapabilities()));
        List<String> warnings = warningsOf(() -> config(values, declaration()));
        assertTrue(warnings.isEmpty(), "一致时不应告警：" + warnings);
    }

    @Test
    void summaryServerIsGrantedByCreateDecision() {
        var config = config(valid(), declaration());
        var plan = new CapabilityPlanner().plan(config.mockCapabilities(), config.profile(),
                Set.of(CapabilityIds.SUMMARY_SERVER_GENERATED),
                config.entitlement().supportedCapabilities(), config.capabilityLimits());
        CapabilityDecision decision = plan.decisions().stream()
                .filter(item -> CapabilityIds.SUMMARY_SERVER_GENERATED.equals(item.capability()))
                .findFirst().orElseThrow();
        assertEquals(CapabilityDecision.Action.ENABLE, decision.action());
        assertEquals(List.of("model.summary.server"), decision.affectedWorkflows());
        assertEquals(List.of("summary.structured"), decision.producedArtifacts());
        // 未请求时只能 DISABLE，不能因为没有执行映射而失败。
        var notRequested = new CapabilityPlanner().plan(config.mockCapabilities(), config.profile(),
                Set.of(), config.entitlement().supportedCapabilities(), config.capabilityLimits());
        CapabilityDecision disabled = notRequested.decisions().stream()
                .filter(item -> CapabilityIds.SUMMARY_SERVER_GENERATED.equals(item.capability()))
                .findFirst().orElseThrow();
        assertEquals(CapabilityDecision.Action.DISABLE, disabled.action());
    }

    @Test
    void environmentOnlySummaryServerAlsoGetsExecutionMapping() {
        // 旧环境变量路径同样不需要额外登记：summary.server 的执行映射由核心统一提供，
        // 不会因为“未知能力”被静默丢掉或缺少 workflow／artifact 而拒绝启动。
        Map<String, String> values = valid();
        values.put(MeetingDeploymentConfig.PROFILE_OPTIONAL_CAPABILITIES,
                CapabilityIds.SUMMARY_SERVER_GENERATED);
        values.put(MeetingDeploymentConfig.ENTITLEMENT_SUPPORTED_CAPABILITIES,
                CapabilityIds.TRANSCRIPT_REALTIME + "," + CapabilityIds.SUMMARY_SERVER_GENERATED);
        values.put(MeetingDeploymentConfig.MODEL_CAPABILITIES,
                CapabilityIds.TRANSCRIPT_REALTIME + "," + CapabilityIds.SUMMARY_SERVER_GENERATED);
        var config = MeetingDeploymentConfig.fromEnvironmentWithExternalIdentity(values,
                "trusted-audience", "trusted-client");
        assertEquals(List.of("model.summary.server"),
                config.profile().affectedWorkflowsOf(CapabilityIds.SUMMARY_SERVER_GENERATED));
        assertEquals(List.of("summary.structured"),
                config.profile().producedArtifactsOf(CapabilityIds.SUMMARY_SERVER_GENERATED));
    }

    @Test
    void declarationRejectsOverlapAndUncoveredRequiredCapabilities() {
        assertThrows(IllegalArgumentException.class, () -> HostCapabilityDeclaration.of(
                Set.of(CapabilityIds.TRANSCRIPT_REALTIME),
                Set.of(CapabilityIds.TRANSCRIPT_REALTIME),
                Set.of(CapabilityIds.TRANSCRIPT_REALTIME),
                Set.of(CapabilityIds.TRANSCRIPT_REALTIME)));
        assertThrows(IllegalArgumentException.class, () -> HostCapabilityDeclaration.of(
                Set.of(CapabilityIds.TRANSCRIPT_REALTIME), Set.of(),
                Set.of(), Set.of(CapabilityIds.TRANSCRIPT_REALTIME)));
        assertThrows(IllegalArgumentException.class, () -> HostCapabilityDeclaration.of(
                Set.of("Transcript.Realtime"), Set.of(), Set.of(), Set.of()));
        var declaration = declaration();
        assertThrows(UnsupportedOperationException.class,
                () -> declaration.requiredCapabilities().add("other.capability"));
    }

    /** 全开声明：字幕必选，互译／结构化纪要／服务端纪要／本场发言人可选。 */
    private static HostCapabilityDeclaration declaration() {
        return HostCapabilityDeclaration.of(
                Set.of(CapabilityIds.TRANSCRIPT_REALTIME),
                Set.of(CapabilityIds.TRANSLATION_REALTIME, CapabilityIds.SUMMARY_STRUCTURED,
                        CapabilityIds.SUMMARY_SERVER_GENERATED,
                        CapabilityIds.SPEAKER_DIARIZATION_SESSION),
                Set.of(CapabilityIds.TRANSCRIPT_REALTIME, CapabilityIds.TRANSLATION_REALTIME,
                        CapabilityIds.SUMMARY_STRUCTURED,
                        CapabilityIds.SUMMARY_SERVER_GENERATED,
                        CapabilityIds.SPEAKER_DIARIZATION_SESSION),
                Set.of(CapabilityIds.TRANSCRIPT_REALTIME, CapabilityIds.TRANSLATION_REALTIME,
                        CapabilityIds.SUMMARY_STRUCTURED,
                        CapabilityIds.SUMMARY_SERVER_GENERATED,
                        CapabilityIds.SPEAKER_DIARIZATION_SESSION));
    }

    private static MeetingDeploymentConfig config(Map<String, String> values,
            HostCapabilityDeclaration declaration) {
        return MeetingDeploymentConfig.fromEnvironmentWithExternalIdentity(values,
                "trusted-audience", "trusted-client", declaration);
    }

    /** 捕获公共配置类自身的 WARNING 文本；不依赖外部日志框架。 */
    private static List<String> warningsOf(Runnable action) {
        List<LogRecord> records = Collections.synchronizedList(new ArrayList<>());
        Handler handler = new Handler() {
            @Override public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    records.add(record);
                }
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        Logger logger = Logger.getLogger(MeetingDeploymentConfig.class.getName());
        logger.addHandler(handler);
        try {
            action.run();
        } finally {
            logger.removeHandler(handler);
        }
        List<String> messages = new ArrayList<>();
        for (LogRecord record : records) {
            // JUL 用 MessageFormat 占位符；只取渲染后的文本用于断言。
            messages.add(record.getParameters() == null
                    ? record.getMessage()
                    : MessageFormat.format(record.getMessage(), record.getParameters()));
        }
        return messages;
    }

    private static Map<String, String> valid() {
        Map<String, String> values = new HashMap<>();
        values.put(DatabaseRuntimeConfig.DATABASE_URL,
                "jdbc:mysql://unreachable.example.invalid:3306/demo");
        values.put(DatabaseRuntimeConfig.DATABASE_USER, "demo-user");
        values.put(DatabaseRuntimeConfig.DATABASE_PASSWORD, "db-secret-sentinel");
        values.put(MeetingDeploymentConfig.DEPLOYMENT_SCOPE, "demo-deployment");
        values.put(MeetingDeploymentConfig.PROFILE_ID, "demo-profile");
        values.put(MeetingDeploymentConfig.PROFILE_REVISION, "2");
        values.put(MeetingDeploymentConfig.PROFILE_REQUIRED_CAPABILITIES,
                CapabilityIds.TRANSCRIPT_REALTIME);
        values.put(MeetingDeploymentConfig.ENTITLEMENT_SUPPORTED_CAPABILITIES,
                CapabilityIds.TRANSCRIPT_REALTIME);
        values.put(MeetingDeploymentConfig.MOCK_CAPABILITIES,
                CapabilityIds.TRANSCRIPT_REALTIME);
        values.put(MeetingDeploymentConfig.MODEL_CAPABILITIES,
                CapabilityIds.TRANSCRIPT_REALTIME);
        values.put(MeetingDeploymentConfig.ENTITLEMENT_MAX_DURATION_MS, "3600000");
        values.put(MeetingDeploymentConfig.ENTITLEMENT_GRANT_TTL_MS, "600000");
        values.put(MeetingDeploymentConfig.ENTITLEMENT_RESERVATION_TTL_MS, "300000");
        values.put(MeetingDeploymentConfig.ESTIMATED_DURATION_MS, "600000");
        values.put(MeetingDeploymentConfig.MAX_MEETING_DURATION_MS, "3600000");
        values.put(MeetingDeploymentConfig.MAX_AUDIO_FRAME_BYTES, "32000");
        values.put(MeetingDeploymentConfig.EVENT_REPLAY_WINDOW, "100");
        values.put(MeetingDeploymentConfig.PAGE_TOKEN_SECRET, SECRET);
        values.put(MeetingDeploymentConfig.WSS_ORIGIN, "wss://meeting.example.invalid");
        values.put(MeetingDeploymentConfig.WSS_TOKEN_TTL_MS, "300000");
        return values;
    }
}
