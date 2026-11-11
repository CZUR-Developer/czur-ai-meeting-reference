package com.czur.reference.domain.capability;

/**
 * 首版公共模型能力标识。
 *
 * <p>常量名称只方便 Java 调用，协议中的字符串值才是跨 Android、后端和
 * Web 的稳定标识。新增能力必须先更新公共协议、契约 Fixture 和 Profile，
 * 不能通过模型厂商名称或返回字段存在性临时推断。</p>
 */
public final class CapabilityIds {
    /** 模型可持续输出带修订语义的实时字幕。 */
    public static final String TRANSCRIPT_REALTIME = "transcript.realtime";
    /** 模型可输出可持久化的最终转写。 */
    public static final String TRANSCRIPT_FINAL = "transcript.final";
    /** 模型可随实时字幕输出译文。 */
    public static final String TRANSLATION_REALTIME = "translation.realtime";
    /** 模型可在单场会议内区分临时发言人。 */
    public static final String SPEAKER_DIARIZATION_SESSION = "speaker.diarization.session";
    /** 模型可把声纹身份关联到不同会议。 */
    public static final String SPEAKER_IDENTITY_CROSS_MEETING = "speaker.identity.cross_meeting";
    /** 产品允许终端用户修改发言人的显示名称。 */
    public static final String SPEAKER_LABEL_EDIT = "speaker.label.edit";
    /** 模型可生成符合公共结构的会议纪要。 */
    public static final String SUMMARY_STRUCTURED = "summary.structured";

    /** 服务端按客户端语言列表生成纪要；只在生成 worker 已装配时授予。 */
    public static final String SUMMARY_SERVER_GENERATED = "summary.server";

    private CapabilityIds() {
    }
}
