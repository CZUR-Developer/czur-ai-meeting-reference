package com.czur.reference.application.model;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 后端为单场会议向模型申请临时连接材料；不接收或转发音频。 */
public interface ModelSessionIssuer {
    /**
     * 根据已持久化的本场事实与服务端能力决策签发临时模型材料。
     *
     * <p>每次 {@code :modelSession} 领取都会调用：首次 Create 后一次，模型重连续接时
     * 还会再次调用。实现必须可重复执行，每次返回新的短期材料，不能把首次结果缓存成
     * 全局单例，也不能依赖“每场只调用一次”做一次性初始化。</p>
     *
     * @param request 已认证且已持久化的同场ID、语言与能力事实；翻译开关只能来自
     *                本场能力决策，不能由调用方请求参数或模型全局配置推断
     * @return Adapter 标识及短期连接材料；不得在日志中输出完整返回值
     */
    Session issue(Request request);

    /**
     * 仅续领独立文本翻译材料，不创建新的 ASR 任务。旧的一体化模型无需实现，返回 null。
     * 调用方仍须核验本场归属、活动状态以及首次冻结的翻译授权。
     */
    default TranslationSessionIssuer.Session issueTranslation(Request request) { return null; }

    /**
     * 模型签发所需的最小本场事实。语言保留公开协议的 IETF 代码；具体厂商代码
     * 由 Adapter 签发器映射。目标语言仅代表 Create 时选择的语言，
     * {@code translationEnabled} 才代表产品、权益、模型和部署限制均已允许翻译。
     * {@code meetingId} 来自已经验证归属的持久会议；需要同场ID的部署发行器直接
     * 使用它，不从客户端正文另取或生成ID。旧三参数调用保留null，默认发行器
     * 仍只消费语言和翻译决策，不因此要求旧调用提供会议ID。
     */
    record Request(String sourceLanguage, List<String> targetLanguages,
                   boolean translationEnabled, String meetingId, boolean multilingualInput) {
        public Request {
            requireText("sourceLanguage", sourceLanguage);
            Objects.requireNonNull(targetLanguages, "targetLanguages");
            targetLanguages = List.copyOf(targetLanguages);
            for (String targetLanguage : targetLanguages) {
                requireText("targetLanguages element", targetLanguage);
            }
            if (meetingId != null) {
                requireText("meetingId", meetingId);
            }
        }

        /**
         * 保留旧签名和行为：旧互译请求同时启用双语识别，普通字幕仍为单语。
         * 独立翻译组合可使用五参构造，只关闭 ASR 原生译文并保留双语识别。
         */
        public Request(String sourceLanguage, List<String> targetLanguages,
                boolean translationEnabled, String meetingId) {
            this(sourceLanguage, targetLanguages, translationEnabled, meetingId, translationEnabled);
        }

        /** 保留原调用签名；未知同场ID不伪造为另一个会议。 */
        public Request(String sourceLanguage, List<String> targetLanguages,
                boolean translationEnabled) {
            this(sourceLanguage, targetLanguages, translationEnabled, null);
        }

        @Override public String toString() {
            return "ModelSessionIssuer.Request[targetCount=" + targetLanguages.size()
                    + ", translationEnabled=" + translationEnabled
                    + ", multilingualInput=" + multilingualInput + "]";
        }
    }

    /** endpoint 可自带凭据；调用者只在认证响应中返回，绝不写日志或会议事实。 */
    record Session(String adapterId, String endpoint, Instant expiresAt,
                   Map<String, String> initParams,
                   TranslationSessionIssuer.Session translationSession) {
        public Session {
            requireText("adapterId", adapterId);
            Objects.requireNonNull(endpoint, "endpoint");
            URI uri;
            try {
                uri = URI.create(endpoint);
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("endpoint must be a valid URI", invalid);
            }
            String scheme = uri.getScheme();
            if (!("ws".equalsIgnoreCase(scheme) || "wss".equalsIgnoreCase(scheme))
                    || uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IllegalArgumentException(
                        "endpoint must be an absolute ws/wss URI with a host");
            }
            Objects.requireNonNull(expiresAt, "expiresAt");
            Objects.requireNonNull(initParams, "initParams");
            initParams = Map.copyOf(initParams);
        }

        /** 保留原发行器的构造签名；没有独立翻译时保持原生实时模型路径。 */
        public Session(String adapterId, String endpoint, Instant expiresAt,
                Map<String, String> initParams) {
            this(adapterId, endpoint, expiresAt, initParams, null);
        }

        /** JoinUrl 与 TaskId 均属于临时材料，避免 record 默认诊断文本泄露。 */
        @Override public String toString() {
            return "ModelSessionIssuer.Session[adapterId=" + adapterId
                    + ", materials=REDACTED]";
        }
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank() || !value.equals(value.trim())) {
            throw new IllegalArgumentException(name + " must be non-blank and trimmed");
        }
    }
}
