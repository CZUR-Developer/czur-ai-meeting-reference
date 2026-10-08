package com.czur.aimeeting.reference.ui;

import android.content.Context;
import android.graphics.ImageDecoder;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import com.czur.aimeeting.reference.R;
import java.io.IOException;

/**
 * 国内版声音指示的薄显示控件，复用 ai_trans_voice.webp 的原始资源及高度响应曲线。
 *
 * <p>页面和悬浮窗只传同场 SDK 的 0～100 电平，本控件不订阅音源、不计算 PCM、
 * 不参与识别或计费。可用于 XML 或直接构造；尺寸由宿主布局决定。Android 28 及以上
 * 使用平台 WebP 动画，26／27 显示同素材的静态帧并保留电平高度响应。
 */
public final class CzurVoiceLevelView extends FrameLayout {
    private static final String TAG = "AiMeetingReferenceUi";
    private final ImageView image;
    private int level;

    public CzurVoiceLevelView(Context context) { this(context, null); }

    public CzurVoiceLevelView(Context context, AttributeSet attributes) {
        super(context, attributes);
        image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.FIT_XY);
        addView(image, new LayoutParams(-1, -1, Gravity.CENTER));
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                image.setImageDrawable(ImageDecoder.decodeDrawable(
                        ImageDecoder.createSource(getResources(), R.raw.czur_overlay_voice)));
            } catch (IOException resourceFailure) {
                // 仅为素材解码失败提供同图静态呈现；音源与会议不受 UI 解码影响。
                Log.w(TAG, "声音动画解码失败，使用静态素材", resourceFailure);
                image.setImageResource(R.raw.czur_overlay_voice);
            }
        } else image.setImageResource(R.raw.czur_overlay_voice);
        image.setScaleY(0.3f);
        setContentDescription("输入声音");
    }

    /**
     * 主线程传入 SDK 的正式显示电平；0为当前输入无声、100为显示满幅。
     * 只使用传入值改变声音图的高度，不显示诊断数值，不再次打开麦克风。
     */
    public void setLevel(int value) {
        level = Math.max(0, Math.min(100, value));
        float normalized = level / 100f;
        float height = normalized < 0.1f ? 0.3f
                : 0.1f + 0.9f * (1f - (float) Math.pow(1f - (normalized - 0.1f) / 0.9f, 4));
        image.animate().cancel();
        image.animate().scaleY(height).setDuration(300).start();
    }

    /** 最近一次用于呈现的正式电平，仅供宿主回归确认，不是第二份音源状态。 */
    public int getLevel() { return level; }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (Build.VERSION.SDK_INT >= 28) {
            Drawable drawable = image.getDrawable();
            if (drawable instanceof AnimatedImageDrawable) ((AnimatedImageDrawable) drawable).start();
        }
    }

    @Override protected void onDetachedFromWindow() {
        image.animate().cancel();
        if (Build.VERSION.SDK_INT >= 28) {
            Drawable drawable = image.getDrawable();
            if (drawable instanceof AnimatedImageDrawable) ((AnimatedImageDrawable) drawable).stop();
        }
        super.onDetachedFromWindow();
    }
}
