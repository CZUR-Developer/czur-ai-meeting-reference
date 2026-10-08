package com.czur.aimeeting.reference.ui;

import android.content.Context;
import android.util.TypedValue;
import android.widget.TextView;
import com.czur.aimeeting.ui.SubtitlePart;
import com.czur.aimeeting.ui.SubtitleTextView;
import java.util.Collections;

/**
 * 提供 meetingUiAar 时编译的字幕薄工厂。页面与窗口通过同一入口使用 SDK UI 工具，
 * 只消费已投影的文字和状态，不判断语种、方向或左右栏，不持有会议生命周期。
 * 伙伴可在本工厂换用自己的控件；音源、模型通信及会议收口无需修改。
 */
public final class SubtitleViewFactory {
    private SubtitleViewFactory() { }

    /**
     * 在主线程创建字幕View。参考App保留既有sp字号，在此换算为工具要求的明确px；
     * 工具不依赖App的单位或屏幕适配框架。固定行高为当前字号的1.3倍。
     * @param textSizeSp 宿主选定的字号，单位sp
     * @param maxVisibleLines 最近可见行数；历史列表单元传Integer.MAX_VALUE保留整段
     * @param reserveLineSlots 窗口可保留行槽位；页面历史单元通常关闭
     */
    public static TextView create(Context context, float textSizeSp, int maxVisibleLines,
            boolean reserveLineSlots) {
        float textSizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, textSizeSp,
                context.getResources().getDisplayMetrics());
        SubtitleTextView view = new SubtitleTextView(context);
        view.setTextSizePx(textSizePx);
        view.setFixedLineHeightPx(Math.round(textSizePx * 1.3f));
        view.setMaxVisibleLines(maxVisibleLines);
        view.setReserveLineSlots(reserveLineSlots);
        return view;
    }

    /**
     * 主线程全量替换本单元的一段文字；占位也经此入口提交，避免重排恢复旧输入。
     * @param segmentId 已投影的业务段ID；占位可传空串，工厂不排序或追加
     * @param text 本次完整文字，原文／译文各用自己的内容
     * @param alpha DisplaySnapshotPresentation已按该文字final产生的1.0或0.8；
     *              这里只将呈现状态映射到SubtitlePart，不重新解释模型事件
     */
    public static void render(TextView view, String segmentId, CharSequence text, float alpha) {
        SubtitleTextView subtitle = (SubtitleTextView) view;
        subtitle.setParts(Collections.singletonList(new SubtitlePart(segmentId, text, alpha == 1.0f)));
    }
}
