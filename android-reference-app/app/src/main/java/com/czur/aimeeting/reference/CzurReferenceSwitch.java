package com.czur.aimeeting.reference;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.widget.CheckBox;

/**
 * 国内CZSwitch的纯显示摘取：左侧文案、右侧蓝白ON/OFF胶囊，保留原生CheckBox语义。
 * 来源Starry/base/UILib/.../CZSwitch.kt；只摘配色、76×40比例及拇指位置，
 * 不引用私有属性、日志、pt扩展或业务状态。SDK能力仍由Activity决定，控件不做校验。
 * setChecked、enabled及监听器与标准CheckBox相同；伙伴可替换该View。
 */
public final class CzurReferenceSwitch extends CheckBox {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final int BLUE = Color.rgb(88, 121, 252);
    public CzurReferenceSwitch(Context context, AttributeSet attributes) {
        super(context, attributes);
        setButtonDrawable(null);
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        // 标题30字／普通24字共用76×40开关，不能用字号决定胶囊尺寸。
        float unit = getHeight() / 48f;
        float width = 76 * unit, height = 40 * unit;
        float right = getWidth(), left = right - width, top = (getHeight() - height) / 2f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(isChecked() ? BLUE : Color.rgb(241, 243, 254));
        canvas.drawRoundRect(left, top, right, top + height, height / 2, height / 2, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(BLUE); paint.setStrokeWidth(2 * unit);
        canvas.drawRoundRect(left + unit, top + unit, right - unit, top + height - unit,
                height / 2, height / 2, paint);
        paint.setStyle(Paint.Style.FILL);
        float radius = height / 2 - 6 * unit;
        float thumbX = isChecked() ? right - height / 2 : left + height / 2;
        paint.setColor(isChecked() ? Color.WHITE : BLUE);
        canvas.drawCircle(thumbX, top + height / 2, radius, paint);
        paint.setColor(isChecked() ? Color.WHITE : BLUE); paint.setTextSize(16 * unit);
        paint.setTextAlign(isChecked() ? Paint.Align.LEFT : Paint.Align.RIGHT);
        float baseline = top + (height - paint.getFontMetrics().top - paint.getFontMetrics().bottom) / 2;
        canvas.drawText(isChecked() ? "ON" : "OFF", isChecked() ? left + 10 * unit : right - 10 * unit,
                baseline, paint);
    }
    @Override public void setChecked(boolean checked) { super.setChecked(checked); invalidate(); }
    @Override public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        setAlpha(enabled ? 1f : 0.5f);
    }
}
