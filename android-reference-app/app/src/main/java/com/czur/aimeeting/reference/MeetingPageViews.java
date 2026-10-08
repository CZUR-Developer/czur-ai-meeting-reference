package com.czur.aimeeting.reference;

import android.app.Activity;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.ScrollView;
import android.widget.TextView;
import com.czur.aimeeting.reference.ui.CzurVoiceLevelView;
import com.czur.aimeeting.reference.ui.SubtitleViewFactory;

/**
 * App侧页面装配，只呈现国内版布局与SDK快照，不创建客户端或第二份会议状态。
 * 国内样式、语言控件、固定控制和发言方双栏由可替换资源提供；参考版仍独立登录且仅Web结果。
 * 伙伴可通过既有Assembly替换Factory，不需要修改音源、Adapter或会议收口。
 */
public final class MeetingPageViews {
    /** 构建期替换页面，返回同组控件供Activity接线；工厂不持有Activity。 */
    public interface Factory { MeetingPageViews create(Activity activity); }
    public static final class DefaultFactory implements Factory {
        @Override public MeetingPageViews create(Activity activity) {
            return new MeetingPageViews((FrameLayout) LayoutInflater.from(activity)
                    .inflate(R.layout.reference_meeting_page, null));
        }
    }

    public final FrameLayout root;
    public final View setupCard, meetingPanel, transcriptScroll, bilingualArea, targetContainer;
    public final LinearLayout controls, transcriptList, bilingualHeader, leftList, rightList, pluginSettingsHost;
    public final TextView setupTitle, meetingTitle, elapsed, levelText, status, capabilities, leftTitle, rightTitle, placeholder;
    public final Button account, settings, start, stop, overlay, swapColumns, swapLanguages, retry, endResult;
    public final Spinner sourceLanguage, targetLanguage;
    public final CheckBox summary, presentation;
    public final RadioButton transcriptMode, translationMode;
    public final RadioGroup modes, results;
    /** 原隐藏诊断值继续接线；正常用户界面只使用国内音量动画。 */
    public final ProgressBar level;
    public final CzurVoiceLevelView voiceLevel;
    private final ReferenceUiScale scale;
    private final FrameLayout singleControlsSlot, bilingualControlsSlot;

    /** 在主线程创建，尺寸基准1920×1080；资源来源见app/UI-RESOURCE-SOURCES.md。 */
    public MeetingPageViews(FrameLayout root) {
        this.root = root;
        setupCard = root.findViewById(R.id.setup_card);
        meetingPanel = root.findViewById(R.id.meeting_panel);
        transcriptScroll = root.findViewById(R.id.transcript_scroll);
        bilingualArea = root.findViewById(R.id.bilingual_text_area);
        targetContainer = root.findViewById(R.id.target_language_container);
        controls = root.findViewById(R.id.meeting_controls);
        transcriptList = root.findViewById(R.id.transcript_list);
        bilingualHeader = root.findViewById(R.id.bilingual_header);
        leftList = root.findViewById(R.id.left_text_list);
        rightList = root.findViewById(R.id.right_text_list);
        pluginSettingsHost = root.findViewById(R.id.result_settings_host);
        setupTitle = root.findViewById(R.id.setup_title);
        meetingTitle = root.findViewById(R.id.meeting_title);
        elapsed = root.findViewById(R.id.elapsed);
        levelText = root.findViewById(R.id.audio_level_text);
        status = root.findViewById(R.id.status);
        capabilities = root.findViewById(R.id.capabilities);
        leftTitle = root.findViewById(R.id.left_language_title);
        rightTitle = root.findViewById(R.id.right_language_title);
        account = root.findViewById(R.id.account_button);
        settings = root.findViewById(R.id.settings_button);
        start = root.findViewById(R.id.start_meeting);
        stop = root.findViewById(R.id.stop_meeting);
        overlay = root.findViewById(R.id.overlay_button);
        swapColumns = root.findViewById(R.id.swap_columns);
        swapLanguages = root.findViewById(R.id.swap_languages);
        retry = root.findViewById(R.id.retry_result);
        endResult = root.findViewById(R.id.end_result);
        sourceLanguage = root.findViewById(R.id.source_language);
        targetLanguage = root.findViewById(R.id.target_language);
        summary = root.findViewById(R.id.summary_enabled);
        presentation = root.findViewById(R.id.presentation_capture);
        transcriptMode = root.findViewById(R.id.transcript_mode);
        translationMode = root.findViewById(R.id.translation_mode);
        modes = root.findViewById(R.id.mode_group);
        results = root.findViewById(R.id.result_group);
        level = root.findViewById(R.id.audio_level);
        voiceLevel = root.findViewById(R.id.voice_level);
        singleControlsSlot = root.findViewById(R.id.single_controls_slot);
        bilingualControlsSlot = root.findViewById(R.id.bilingual_controls_slot);
        placeholder = SubtitleViewFactory.create(root.getContext(), 24, 3, false);
        placeholder.setTextColor(Color.WHITE);
        SubtitleViewFactory.render(placeholder, "", "记录中…", 0.8f);
        transcriptList.addView(placeholder);
        setupCard.setClipToOutline(true);
        scale = ReferenceUiScale.bind(root);
        SubtitleViewFactory.setLineHeightPx(placeholder, scale.px(36));
        icon(stop, R.drawable.czur_ref_stop, 40, 40);
        icon(overlay, R.drawable.czur_ref_minimize, 40, 40);
        icon(swapColumns, R.drawable.czur_ref_swap_columns, 52, 28);
        icon(swapLanguages, R.drawable.czur_ref_swap_languages, 49, 24);
    }

    private void icon(Button button, int resource, int width, int height) {
        var drawable = root.getContext().getDrawable(resource);
        drawable.setBounds(0, 0, scale.px(width), scale.px(height));
        button.setCompoundDrawables(null, drawable, null, null);
        button.setGravity(Gravity.CENTER);
    }

    /** 语言选择只负责样式；标签与code仍来自原Activity的后端语言声明。 */
    public void styleLanguageItem(TextView text, boolean popup, boolean selected) {
        text.setTextSize(TypedValue.COMPLEX_UNIT_PX, scale.px(24));
        text.setTextColor(root.getContext().getColor(R.color.reference_blue));
        text.setGravity(Gravity.CENTER_VERTICAL);
        text.setPadding(scale.px(24), 0, scale.px(16), 0);
        text.setMinHeight(scale.px(50));
        ViewGroup.LayoutParams params = text.getLayoutParams();
        if (params != null) { params.height = scale.px(50); text.setLayoutParams(params); }
        int iconId = popup ? R.drawable.czur_ref_language_selected : R.drawable.czur_ref_language_arrow;
        var arrow = root.getContext().getDrawable(iconId);
        arrow.setTint(root.getContext().getColor(R.color.reference_blue));
        arrow.setBounds(0, 0, scale.px(popup ? 24 : 14), scale.px(popup ? 24 : 8));
        text.setCompoundDrawables(null, null, !popup || selected ? arrow : null, null);
        if (popup) text.setBackgroundColor(Color.parseColor(selected ? "#DADFFD" : "#F1F3FE"));
    }

    /** 动态结果动作保留原生单选语义，使用国内50×50勾选框及24字号。 */
    public void styleResultChoice(RadioButton choice) {
        choice.setTextColor(root.getContext().getColor(R.color.reference_ink));
        choice.setTextSize(TypedValue.COMPLEX_UNIT_PX, scale.px(24));
        choice.setButtonDrawable(R.drawable.czur_ref_check);
        choice.setButtonTintList(null);
        choice.setPadding(scale.px(24), 0, 0, 0);
        choice.setCompoundDrawablePadding(scale.px(24));
        choice.setMinHeight(scale.px(58));
    }

    /** 改变模式只影响呈现与当前选项，能力与本场快照继续由原Activity决定。 */
    public void showSetupMode(boolean translation) {
        targetContainer.setVisibility(translation ? View.VISIBLE : View.GONE);
        swapLanguages.setVisibility(translation ? View.VISIBLE : View.GONE);
        presentation.setVisibility(translation ? View.GONE : View.VISIBLE);
        root.findViewById(R.id.presentation_hint).setVisibility(translation ? View.GONE : View.VISIBLE);
        root.findViewById(R.id.translation_illustration).setVisibility(translation ? View.VISIBLE : View.GONE);
        ((TextView)root.findViewById(R.id.language_hint)).setText(
                translation ? "提示：两种语言的人群，分坐左右两侧" : "语言");
        TextView languageHint = root.findViewById(R.id.language_hint);
        languageHint.setTextSize(TypedValue.COMPLEX_UNIT_PX, scale.px(translation ? 20 : 30));
        languageHint.setTypeface(null, translation ? android.graphics.Typeface.NORMAL : android.graphics.Typeface.BOLD);
        setupTitle.setText(translation ? "AI双语互译" : "AI会议纪要");
        ((TextView)root.findViewById(R.id.setup_subtitle)).setText(
                translation ? "（将两种语言进行互译）" : "（实时将声音转文字）");
    }

    /** 国内原文白色、译文黄色，24字号／36固定行高；不判断发言侧或模型数据。 */
    public TextView textRow(float ignoredLegacySp, boolean translated) {
        TextView view = SubtitleViewFactory.create(root.getContext(), scale.textSp(24), Integer.MAX_VALUE, false);
        SubtitleViewFactory.setLineHeightPx(view, scale.px(36));
        view.setTextColor(translated ? root.getContext().getColor(R.color.reference_translation) : Color.WHITE);
        view.setPadding(0, 0, 0, scale.px(translated ? 16 : 4));
        return view;
    }

    /** 仅隐藏／展示View；离开页面和窗口切换不改变Service会议。 */
    public void showMeeting(boolean active) {
        setupCard.setVisibility(active ? View.GONE : View.VISIBLE);
        meetingPanel.setVisibility(active ? View.VISIBLE : View.GONE);
        status.setVisibility(active ? View.GONE : View.VISIBLE);
    }

    /**
     * 国内大互译控制区位于两发言栏之间，单语控制区位于字幕上方。
     * 移动同一组View，不复制停止／电平／交换状态，也不重复注册业务回调。
     */
    public void showBilingual(boolean bilingual) {
        bilingualHeader.setVisibility(bilingual ? View.VISIBLE : View.GONE);
        bilingualArea.setVisibility(bilingual ? View.VISIBLE : View.GONE);
        transcriptScroll.setVisibility(bilingual ? View.GONE : View.VISIBLE);
        swapColumns.setVisibility(bilingual ? View.VISIBLE : View.GONE);
        singleControlsSlot.setVisibility(bilingual ? View.GONE : View.VISIBLE);
        FrameLayout slot = bilingual ? bilingualControlsSlot : singleControlsSlot;
        if (controls.getParent() != slot) {
            ((ViewGroup)controls.getParent()).removeView(controls);
            slot.addView(controls, new FrameLayout.LayoutParams(-1, -1));
        }
        controls.setOrientation(bilingual ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        controls.setGravity(bilingual ? Gravity.TOP | Gravity.CENTER_HORIZONTAL : Gravity.CENTER);
        for (int i = 0; i < controls.getChildCount(); i++) {
            var child = controls.getChildAt(i);
            var params = (LinearLayout.LayoutParams)child.getLayoutParams();
            params.bottomMargin = bilingual ? scale.px(26) : 0;
            params.leftMargin = bilingual ? 0 : scale.px(10);
            params.rightMargin = bilingual ? 0 : scale.px(10);
            child.setLayoutParams(params);
        }
        meetingPanel.setBackgroundResource(bilingual ? R.drawable.czur_ref_translation_background
                : R.drawable.czur_ref_subtitle_background);
    }

    /** 国内字幕默认跟随最新内容；用户向上查看历史时不抢回滚动位置。 */
    public boolean followsLatest(int scrollId) {
        ScrollView scroll = root.findViewById(scrollId);
        return scroll.getChildCount() == 0 || scroll.getScrollY() + scroll.getHeight()
                >= scroll.getChildAt(0).getHeight() - scale.px(36);
    }

    /** 全量投影渲染后按上一帧滚动位置跟随，不请求焦点、不遮挡停止控件。 */
    public void scrollLatest(int scrollId, boolean follow) {
        if (!follow) return;
        ScrollView scroll = root.findViewById(scrollId);
        scroll.post(() -> scroll.scrollTo(0, scroll.getChildCount() == 0 ? 0
                : Math.max(0, scroll.getChildAt(0).getHeight() - scroll.getHeight())));
    }
}
