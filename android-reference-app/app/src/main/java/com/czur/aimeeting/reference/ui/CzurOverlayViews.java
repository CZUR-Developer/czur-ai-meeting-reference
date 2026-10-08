package com.czur.aimeeting.reference.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Typeface;
import android.graphics.drawable.AnimatedImageDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.czur.aimeeting.client.display.MeetingDisplaySnapshot;
import com.czur.aimeeting.reference.R;
import com.czur.aimeeting.reference.meeting.DisplaySnapshotPresentation;
import com.github.penfeizhou.animation.apng.APNGDrawable;
import com.github.penfeizhou.animation.loader.ResourceStreamLoader;
import java.util.ArrayList;
import java.io.IOException;
import java.util.List;

/**
 * 可编辑的国内版窗口内容：只复刻 View 和资源，不持有会议、模型或音源。
 *
 * <p>几何关系来自国内 layout_floating_subtitle／trans_normal／trans_large／trans_mini，
 * 设计基准 1920×1080。这里将设计数值换算为实际 px，不引入 AutoSize 或 Starry 私有库。
 * 宿主仍由 SDK 的 DefaultMeetingOverlay 管理形态／Mini 恢复／WindowManager 生命周期。
 * 原文／译文按统一 speakerColumn 投影放在同一发言方列，语种和 DOA 不在这里判定。
 */
public final class CzurOverlayViews {
    private static final String TAG = "AiMeetingReferenceUi";
    private static final int WHITE = Color.WHITE, YELLOW = 0xFFFFDB10;
    private final String form;
    private final float scale;
    private final FrameLayout root;
    private final ImageView background, stop, collapse, size, swap, simple, recordingAnimation;
    private final TextView time, subtitle, recording, recordingHint;
    private final CzurVoiceLevelView voice;
    private final TextView leftSource, leftTranslation, rightSource, rightTranslation;
    private final LinearLayout leftColumn, rightColumn;
    private final LinearLayout leftHistory, rightHistory;
    private final ScrollView leftScroll, rightScroll;
    private long lastLeftScroll, lastRightScroll;

    /**
     * 只有薄显示动作。null 表示该宿主没有装配这一显示能力，按钮不出现；普通回调由原
     * Service 提供，形态切换回调由 SDK 默认工具提供。简洁开关只改变普通窗布局。
     */
    public record Actions(Runnable open, Runnable swap, Runnable stop, Runnable collapse,
            Runnable large, Runnable normal, Runnable restore, Runnable toggleSimple) { }

    /**
     * 主线程创建未挂载内容。form 使用 SDK 默认形态标识；host 将本 View 交给已有窗口工具。
     * 不申请悬浮权限，不停止会议，不创建与产品有关的 Provider 或平台联动。
     */
    public CzurOverlayViews(Context context, String form, Actions actions) {
        this.form = form;
        scale = designScale(context);
        root = new FrameLayout(context);
        root.setTag("overlay.panel");
        root.setClipChildren(true);
        background = new ImageView(context);
        background.setScaleType(ImageView.ScaleType.FIT_XY);
        if ("subtitle".equals(form)) background.setImageResource(R.drawable.czur_overlay_subtitle_background);
        else if ("translation-large".equals(form)) background.setImageResource(R.drawable.czur_overlay_large_background);
        else if ("translation-normal".equals(form)) background.setImageResource(R.drawable.czur_overlay_normal_background);
        root.addView(background, new FrameLayout.LayoutParams(-1, -1));
        voice = new CzurVoiceLevelView(context);
        voice.setTag("overlay.level");
        time = label(context, 22, "overlay.open");
        time.setTextColor(0xCCFFFFFF);
        time.setContentDescription(context.getString(R.string.czur_overlay_open));
        time.setOnClickListener(clicked -> actions.open().run());
        stop = icon(context, R.drawable.czur_overlay_stop, R.string.czur_overlay_stop, "overlay.stop", actions.stop());
        collapse = icon(context, R.drawable.czur_overlay_collapse, R.string.czur_overlay_collapse,
                "overlay.collapse", actions.collapse());
        size = icon(context, "translation-large".equals(form) ? R.drawable.czur_overlay_normal : R.drawable.czur_overlay_large,
                "translation-large".equals(form) ? R.string.czur_overlay_normal : R.string.czur_overlay_large,
                "overlay.size", "translation-large".equals(form) ? actions.normal() : actions.large());
        swap = icon(context, R.drawable.czur_overlay_swap, R.string.czur_overlay_swap, "overlay.swap", actions.swap());
        simple = icon(context, R.drawable.czur_overlay_simple, R.string.czur_overlay_simple, "overlay.simple", actions.toggleSimple());
        subtitle = caption(context, "overlay.subtitle", WHITE, 3);
        leftSource = caption(context, "overlay.left", WHITE, 3);
        leftTranslation = caption(context, "overlay.left-translation", YELLOW, 3);
        rightSource = caption(context, "overlay.right", WHITE, 3);
        rightTranslation = caption(context, "overlay.right-translation", YELLOW, 3);
        leftColumn = column(context, leftSource, leftTranslation);
        rightColumn = column(context, rightSource, rightTranslation);
        recording = label(context, 24, "overlay.recording");
        recording.setText(context.getString(R.string.czur_overlay_recording));
        recording.setTextColor(YELLOW);
        recording.setTypeface(null, Typeface.BOLD);
        recordingHint = label(context, 20, "overlay.recording-hint");
        recordingHint.setText(context.getString(R.string.czur_overlay_recording_hint));
        recordingAnimation = new RecordingImage(context);
        leftHistory = history(context, "overlay.history-left-column");
        rightHistory = history(context, "overlay.history-right-column");
        leftScroll = scroll(context, leftHistory, true);
        rightScroll = scroll(context, rightHistory, false);
        if ("mini".equals(form)) {
            MiniImage mini = new MiniImage(context);
            mini.setTag("overlay.restore");
            mini.setContentDescription(context.getString(R.string.czur_overlay_restore));
            mini.setOnClickListener(clicked -> actions.restore().run());
            root.addView(mini, new FrameLayout.LayoutParams(-1, -1));
            put(voice, 10, 15, 61, 30);
            // 声音图覆盖于同一个 Mini 图上，点击其可见区域也应恢复。
            voice.setOnClickListener(clicked -> actions.restore().run());
            background.setVisibility(View.GONE);
        } else if ("subtitle".equals(form)) {
            put(time, 650, 14, 170, 44);
            put(voice, 832, 14, 136, 44);
            put(stop, 968, 14, 40, 40);
            put(collapse, 1028, 14, 40, 40);
            put(subtitle, 40, 82, 1700, 108);
            put(recording, 800, 82, 200, 40);
            put(recordingAnimation, 966, 86, 32, 32);
            put(recordingHint, 660, 126, 480, 36);
        } else if ("translation-large".equals(form)) {
            put(time, 805, 50, 190, 30);
            put(voice, 832, 110, 136, 44);
            put(swap, 874, 184, 52, 28);
            put(stop, 880, 242, 40, 40);
            put(collapse, 880, 312, 40, 40);
            put(size, 880, 382, 40, 40);
            put(leftScroll, 40, 40, 700, 928);
            put(rightScroll, 1060, 40, 700, 928);
        } else {
            put(time, 805, 50, 190, 30);
            put(voice, 832, 98, 136, 44);
            put(swap, 874, 170, 52, 28);
            put(stop, 790, 223, 40, 40);
            put(collapse, 850, 223, 40, 40);
            put(size, 910, 223, 40, 40);
            put(simple, 970, 223, 40, 40);
            put(leftColumn, 40, 40, 700, 216);
            put(rightColumn, 1060, 40, 700, 216);
        }
    }

    public View view() { return root; }

    /**
     * 完整替换呈现输入。多段／修订／final／左右决定已在 SDK 快照中，这里只格式化现有字段。
     * timeText、canStop及电平来自同一个宿主；Mini／隐藏期间仍由 SDK 保存最新快照。
     */
    public void render(MeetingDisplaySnapshot display, String timeText, boolean canStop, int audioLevel,
            boolean simpleMode) {
        // Service 保留完整“已进行／剩余”语义；原窗口只显示经过时长，无须另建计时器。
        time.setText(timeText.replace("已进行 ", "").split(" · ", 2)[0].replace(":", " : "));
        stop.setEnabled(canStop);
        stop.setAlpha(canStop ? 1f : 0.4f);
        voice.setLevel(audioLevel);
        if ("mini".equals(form)) return;
        if ("subtitle".equals(form)) {
            List<DisplaySnapshotPresentation.SubtitleRow> parts = new ArrayList<>();
            for (var row : display.rows()) {
                var part = DisplaySnapshotPresentation.subtitle(row);
                if (part != null && !part.text().isBlank()) parts.add(part);
            }
            boolean hasText = !parts.isEmpty();
            // 直接呈现完整有序快照，SDK控件裁最近三行；迟到修订仍更新原ID，不追加账本。
            SubtitleViewFactory.renderParts(subtitle, parts);
            recording.setVisibility(hasText ? View.GONE : View.VISIBLE);
            recordingAnimation.setVisibility(hasText ? View.GONE : View.VISIBLE);
            recordingHint.setVisibility(hasText ? View.GONE : View.VISIBLE);
            return;
        }
        List<DisplaySnapshotPresentation.SpeakerColumnRow> left = new ArrayList<>(), right = new ArrayList<>();
        for (var row : display.rows()) {
            var column = DisplaySnapshotPresentation.speakerColumn(row, display.swapped());
            if (column.side() == MeetingDisplaySnapshot.Side.A) left.add(column);
            else if (column.side() == MeetingDisplaySnapshot.Side.B) right.add(column);
            // UNRESOLVED 保持未定，不能为填满版面在 UI 推断发言方。
        }
        if ("translation-large".equals(form)) {
            renderHistory(leftHistory, left, "overlay.history-left", "overlay.history-left-translation");
            renderHistory(rightHistory, right, "overlay.history-right", "overlay.history-right-translation");
            if (SystemClock.uptimeMillis() - lastLeftScroll >= 2000)
                leftScroll.post(() -> leftScroll.fullScroll(View.FOCUS_DOWN));
            if (SystemClock.uptimeMillis() - lastRightScroll >= 2000)
                rightScroll.post(() -> rightScroll.fullScroll(View.FOCUS_DOWN));
        } else {
            renderColumn(leftSource, leftTranslation, left);
            renderColumn(rightSource, rightTranslation, right);
            applySimpleMode(simpleMode);
        }
    }

    private void renderColumn(TextView source, TextView translation,
            List<DisplaySnapshotPresentation.SpeakerColumnRow> rows) {
        List<DisplaySnapshotPresentation.SubtitleRow> sources = new ArrayList<>(), translations = new ArrayList<>();
        for (var row : rows) {
            if (!row.source().isBlank()) sources.add(new DisplaySnapshotPresentation.SubtitleRow(
                    row.segmentId(), row.source(), row.sourceAlpha()));
            if (!row.translated().isBlank()) translations.add(new DisplaySnapshotPresentation.SubtitleRow(
                    row.segmentId(), row.translated(), row.translatedAlpha()));
        }
        // 原版普通窗保留已final历史和当前partial；裁行只属于控件，不删改业务快照。
        SubtitleViewFactory.renderParts(source, sources);
        SubtitleViewFactory.renderParts(translation, translations);
    }

    private void renderHistory(LinearLayout history, List<DisplaySnapshotPresentation.SpeakerColumnRow> rows,
            String sourceTag, String translatedTag) {
        // 只维护 View 列表，数据始终来自本次完整快照；不生成句号／句子ID或追加事件账本。
        while (history.getChildCount() > rows.size()) history.removeViewAt(history.getChildCount() - 1);
        for (int index = 0; index < rows.size(); index++) {
            LinearLayout item;
            if (index == history.getChildCount()) {
                item = new LinearLayout(root.getContext());
                item.setOrientation(LinearLayout.VERTICAL);
                item.setPadding(0, 0, 0, px(16));
                item.addView(caption(root.getContext(), sourceTag, WHITE, Integer.MAX_VALUE));
                item.addView(caption(root.getContext(), translatedTag, YELLOW, Integer.MAX_VALUE));
                history.addView(item, new LinearLayout.LayoutParams(-1, -2));
            } else item = (LinearLayout) history.getChildAt(index);
            var row = rows.get(index);
            SubtitleViewFactory.render((TextView) item.getChildAt(0), row.segmentId(), row.source(), row.sourceAlpha());
            SubtitleViewFactory.render((TextView) item.getChildAt(1), row.segmentId(), row.translated(), row.translatedAlpha());
        }
    }

    private void applySimpleMode(boolean enabled) {
        background.setVisibility(enabled ? View.INVISIBLE : View.VISIBLE);
        time.setVisibility(enabled ? View.GONE : View.VISIBLE);
        voice.setVisibility(enabled ? View.GONE : View.VISIBLE);
        swap.setVisibility(enabled ? View.GONE : View.VISIBLE);
        int lines = enabled ? 2 : 3;
        for (TextView cell : new TextView[] { leftSource, leftTranslation, rightSource, rightTranslation }) {
            cell.setMaxLines(lines);
            cell.setShadowLayer(enabled ? px(4) : 0, 0, enabled ? px(2) : 0, enabled ? 0x80000000 : Color.TRANSPARENT);
        }
        position(leftColumn, 40, enabled ? 2 : 40, 700, enabled ? 148 : 216);
        position(rightColumn, 1060, enabled ? 2 : 40, 700, enabled ? 148 : 216);
        int buttonTop = enabled ? 99 : 223;
        position(stop, 790, buttonTop, 40, 40);
        position(collapse, 852, buttonTop, 40, 40);
        position(size, 914, buttonTop, 40, 40);
        position(simple, 976, buttonTop, 40, 40);
        stop.setImageResource(enabled ? R.drawable.czur_overlay_simple_stop : R.drawable.czur_overlay_stop);
        collapse.setImageResource(enabled ? R.drawable.czur_overlay_simple_swap : R.drawable.czur_overlay_collapse);
        size.setImageResource(enabled ? R.drawable.czur_overlay_simple_large : R.drawable.czur_overlay_large);
        simple.setImageResource(enabled ? R.drawable.czur_overlay_simple_exit : R.drawable.czur_overlay_simple);
        simple.setContentDescription(root.getContext().getString(enabled
                ? R.string.czur_overlay_simple_exit : R.string.czur_overlay_simple));
    }

    private TextView label(Context context, int size, String tag) {
        TextView view = new TextView(context);
        view.setTag(tag);
        view.setIncludeFontPadding(false);
        view.setTextColor(WHITE);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * scale);
        view.setGravity(Gravity.CENTER);
        view.setSingleLine(true);
        return view;
    }

    private TextView caption(Context context, String tag, int color, int lines) {
        float textSp = 24 * scale / context.getResources().getDisplayMetrics().scaledDensity;
        TextView view = SubtitleViewFactory.create(context, textSp, lines, false);
        view.setTag(tag);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        SubtitleViewFactory.setLineHeightPx(view, px(36));
        view.setGravity(Gravity.TOP | Gravity.START);
        return view;
    }

    private LinearLayout column(Context context, TextView source, TextView translated) {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.addView(source, new LinearLayout.LayoutParams(-1, -2));
        column.addView(translated, new LinearLayout.LayoutParams(-1, -2));
        return column;
    }

    private ImageView icon(Context context, int drawable, int description, String tag, Runnable action) {
        ImageView view = new ImageView(context);
        view.setTag(tag);
        view.setScaleType(ImageView.ScaleType.FIT_XY);
        view.setImageResource(drawable);
        view.setContentDescription(context.getString(description));
        if (action == null) view.setVisibility(View.GONE);
        else view.setOnClickListener(clicked -> { Log.d(TAG, "窗口操作 " + tag); action.run(); });
        return view;
    }

    private LinearLayout history(Context context, String tag) {
        LinearLayout history = new LinearLayout(context);
        history.setTag(tag);
        history.setOrientation(LinearLayout.VERTICAL);
        return history;
    }

    private ScrollView scroll(Context context, LinearLayout history, boolean left) {
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(history, new ScrollView.LayoutParams(-1, -2));
        scroll.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN || event.getActionMasked() == MotionEvent.ACTION_MOVE
                    || event.getActionMasked() == MotionEvent.ACTION_UP) {
                if (left) lastLeftScroll = SystemClock.uptimeMillis();
                else lastRightScroll = SystemClock.uptimeMillis();
            }
            return false;
        });
        return scroll;
    }

    private void put(View view, int x, int y, int width, int height) {
        root.addView(view);
        position(view, x, y, width, height);
    }

    private void position(View view, int x, int y, int width, int height) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(px(width), px(height));
        params.leftMargin = px(x);
        params.topMargin = px(y);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        view.setLayoutParams(params);
    }

    private int px(int value) { return Math.round(value * scale); }

    /** 本布局与两种宿主统一使用国内1920×1080设计数值，不受Android默认dp密度二次缩放。 */
    public static float designScale(Context context) {
        var metrics = context.getResources().getDisplayMetrics();
        return Math.min(metrics.widthPixels / 1920f, metrics.heightPixels / 1080f);
    }

    /** 原Mini采用相同APNG库；图片动画随内容View实际挂载开始，换形态／隐藏时停止。 */
    private static final class MiniImage extends ImageView {
        private final APNGDrawable animation;
        MiniImage(Context context) {
            super(context);
            setScaleType(ScaleType.FIT_XY);
            animation = new APNGDrawable(new ResourceStreamLoader(context, R.raw.czur_overlay_mini));
            setImageDrawable(animation);
        }
        @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); animation.start(); }
        @Override protected void onDetachedFromWindow() { animation.stop(); super.onDetachedFromWindow(); }
    }

    /** 使用国内“记录中”WebP，不依赖 Glide；展示生命周期随当前内容View。 */
    private static final class RecordingImage extends ImageView {
        RecordingImage(Context context) {
            super(context);
            setScaleType(ScaleType.FIT_XY);
            if (Build.VERSION.SDK_INT >= 28) {
                try {
                    setImageDrawable(ImageDecoder.decodeDrawable(ImageDecoder.createSource(
                            getResources(), R.raw.czur_overlay_recording)));
                } catch (IOException failed) {
                    Log.w(TAG, "记录中动画解码失败，使用静态素材", failed);
                    setImageResource(R.raw.czur_overlay_recording);
                }
            } else setImageResource(R.raw.czur_overlay_recording);
        }
        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (Build.VERSION.SDK_INT >= 28 && getDrawable() instanceof AnimatedImageDrawable)
                ((AnimatedImageDrawable) getDrawable()).start();
        }
        @Override protected void onDetachedFromWindow() {
            if (Build.VERSION.SDK_INT >= 28 && getDrawable() instanceof AnimatedImageDrawable)
                ((AnimatedImageDrawable) getDrawable()).stop();
            super.onDetachedFromWindow();
        }
    }
}
