package com.czur.reference.application.port;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * 后端已经持久接管、可供最终收口使用的输入事实。
 *
 * <p>该事实与 {@link FinalizationFact} 刻意分开：{@code FinalizationFact}
 * 描述的是会议级输入状态以及对 App 可见的接管凭证，而本类型描述的是生成该
 * 凭证之前必须已经完整落盘的输入材料。只有这个事实已经可靠持久化，后续工作
 * 进程才可以在没有原始模型会话和终端进程的情况下继续收口。</p>
 *
 * <p>字段全部是值类型或构造时复制的不可变值。{@code lastAcceptedSequence}
 * 和 {@code audioDurationMs} 使用零表示尚未接收到任何帧或音频时间轴长度为零，
 * 但不允许使用负数。{@code contentDigest} 是输入内容的稳定摘要文本；摘要算法、
 * 编码和最终存储布局由实现边界决定，本契约只要求它是非空文本，避免在尚未冻结
 * 持久化方案时把某一种摘要编码误当成业务状态机。</p>
 *
 * <p>本类型不包含 {@code finalizationReceipt}。receipt 是接管事实提交成功后由
 * 会议级收口端口产生的不透明凭证，不能由输入事实构造器预先生成或在诊断文本中
 * 复述。{@link #toString()} 同样不会输出会议标识、input binding、语言正文或
 * 摘要正文。</p>
 *
 * @param meetingId 归属会议标识；必须为非空文本
 * @param inputBinding 稳定输入绑定标识；必须为非空文本
 * @param lastAcceptedSequence 已被后端接管的最后 stream sequence，允许为零
 * @param audioDurationMs 已接管音频时间轴长度（毫秒），允许为零
 * @param sourceLanguage 输入源语言；必须为非空文本
 * @param targetLanguages 目标语言列表；允许为空，但列表及其元素不可变且不得重复
 * @param acceptedAt 输入事实完成接管的时间；必须存在
 * @param contentDigest 输入内容的稳定摘要文本；必须为非空文本
 */
public record FinalizationInputFact(
        String meetingId,
        String inputBinding,
        long lastAcceptedSequence,
        long audioDurationMs,
        String sourceLanguage,
        List<String> targetLanguages,
        Instant acceptedAt,
        String contentDigest) {

    /**
     * 校验并冻结输入事实。
     *
     * <p>构造函数不修剪或改写字符串，也不把 {@code null} 目标列表解释为空列表，
     * 以便数据库适配器和内存实现对同一非法输入保持相同语义。</p>
     */
    public FinalizationInputFact {
        requireText("meetingId", meetingId);
        requireText("inputBinding", inputBinding);
        if (lastAcceptedSequence < 0) {
            throw new IllegalArgumentException("lastAcceptedSequence must not be negative");
        }
        if (audioDurationMs < 0) {
            throw new IllegalArgumentException("audioDurationMs must not be negative");
        }
        requireText("sourceLanguage", sourceLanguage);
        Objects.requireNonNull(targetLanguages, "targetLanguages");
        targetLanguages = List.copyOf(targetLanguages);
        for (String targetLanguage : targetLanguages) {
            requireText("targetLanguages item", targetLanguage);
        }
        if (new HashSet<>(targetLanguages).size() != targetLanguages.size()) {
            throw new IllegalArgumentException("targetLanguages must be unique");
        }
        Objects.requireNonNull(acceptedAt, "acceptedAt");
        requireText("contentDigest", contentDigest);
    }

    /**
     * 只输出可诊断的规模和存在性信息。
     *
     * <p>不要让 record 的默认输出把 meetingId、input binding、语言值、摘要或
     * 任何未来附加的 opaque receipt 带入日志、异常链或测试诊断。</p>
     */
    @Override
    public String toString() {
        return "FinalizationInputFact[meetingId=redacted"
                + ", inputBinding=redacted"
                + ", lastAcceptedSequence=" + lastAcceptedSequence
                + ", audioDurationMs=" + audioDurationMs
                + ", sourceLanguage=redacted"
                + ", targetLanguageCount=" + targetLanguages.size()
                + ", acceptedAt=" + acceptedAt
                + ", contentDigestPresent=true]";
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
