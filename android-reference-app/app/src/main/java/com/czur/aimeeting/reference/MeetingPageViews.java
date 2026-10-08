package com.czur.aimeeting.reference;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import com.czur.aimeeting.reference.ui.SubtitleViewFactory;

/**
 * App侧薄页面装配：只绑定原生View和呈现样式，不创建客户端、会话或第二份会议状态。
 * 在Activity主线程创建；宿主负责身份、能力、点击回调及Service观察，SDK只提供字幕工具。
 */
public final class MeetingPageViews {
    /** 构建期替换布局入口；返回同一组控件，由原Activity接线，工厂不保留Activity。 */
    public interface Factory { MeetingPageViews create(Activity activity); }

    /** 默认公开布局，可在伙伴App替换资源或通过Assembly登记自己的工厂。 */
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
    public final ProgressBar level;

    /**
     * 自定义工厂可传入带同名控件ID的根布局。仅初始化控件及轻量屏幕尺度，不涉及业务。
     * 大屏按国内988×856／1920×1080比例居中，小窗占用可用区域，内容滚动而操作固定。
     */
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
        placeholder = SubtitleViewFactory.create(root.getContext(), 28, 3, false);
        placeholder.setTextColor(Color.WHITE);
        SubtitleViewFactory.render(placeholder, "", "等待字幕…", 0.8f);
        transcriptList.addView(placeholder);
        GradientDrawable card = new GradientDrawable();
        card.setColor(Color.WHITE);
        card.setCornerRadius(16 * root.getResources().getDisplayMetrics().density);
        setupCard.setBackground(card);
        setupCard.setClipToOutline(true);
        resizeCard(root.getResources().getDisplayMetrics().widthPixels,
                root.getResources().getDisplayMetrics().heightPixels);
        root.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l == or - ol && b - t == ob - ot) return;
            resizeCard(r - l, b - t);
        });
    }

    private void resizeCard(int availableWidth, int availableHeight) {
        float scale = Math.min(availableWidth / 1920f, availableHeight / 1080f);
        float density = root.getResources().getDisplayMetrics().density;
        int width = availableWidth < 800 * density ? Math.max(1, availableWidth - (int)(24 * density))
                : Math.round(988 * scale);
        int parentHeight = ((View)setupCard.getParent()).getHeight();
        int height = Math.min(Math.round(856 * scale), parentHeight > 0 ? parentHeight : availableHeight);
        FrameLayout.LayoutParams size = (FrameLayout.LayoutParams) setupCard.getLayoutParams();
        if (size.width != width || size.height != height) {
            size.width = width;
            size.height = height;
            setupCard.setLayoutParams(size);
        }
    }

    /** 模式只是呈现输入；语种与能力仍由Activity的已发现结果决定。 */
    public void showSetupMode(boolean translation) {
        targetContainer.setVisibility(translation ? View.VISIBLE : View.GONE);
        swapLanguages.setVisibility(translation ? View.VISIBLE : View.GONE);
        presentation.setVisibility(translation ? View.GONE : View.VISIBLE);
        setupTitle.setText(translation ? "AI 双语互译" : summary.isChecked() ? "AI 会议纪要" : "实时字幕");
    }

    /** 已投影文本共用一个字幕工厂；不解析模型、方向或修订状态。 */
    public TextView textRow(float sp, boolean translated) {
        TextView view = SubtitleViewFactory.create(root.getContext(), sp, Integer.MAX_VALUE, false);
        view.setTextColor(translated ? root.getResources().getColor(R.color.reference_translation, null) : Color.WHITE);
        view.setPadding(0, 8, 0, 8);
        return view;
    }

    /** 只切换View可见性；离开、隐藏与窗口切换不触碰Service会议生命周期。 */
    public void showMeeting(boolean active) {
        setupCard.setVisibility(active ? View.GONE : View.VISIBLE);
        meetingPanel.setVisibility(active ? View.VISIBLE : View.GONE);
    }

    public void showBilingual(boolean bilingual) {
        bilingualHeader.setVisibility(bilingual ? View.VISIBLE : View.GONE);
        bilingualArea.setVisibility(bilingual ? View.VISIBLE : View.GONE);
        transcriptScroll.setVisibility(bilingual ? View.GONE : View.VISIBLE);
        swapColumns.setVisibility(bilingual ? View.VISIBLE : View.GONE);
    }
}
