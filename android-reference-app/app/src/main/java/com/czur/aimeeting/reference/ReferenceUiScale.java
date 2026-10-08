package com.czur.aimeeting.reference;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/**
 * 参考页面的轻量设计尺寸适配，基准来自国内1920×1080设计稿。
 * XML的dp/sp数值在本页代表设计单位；加载后把尺寸和字号映射到实际可用区域。
 * 不修改系统DisplayMetrics，不引入Starry私有AutoSize；SDK仍只接收明确的像素字号。
 * 每个实例只持有自己的View树，随页面销毁；布局变化根据首次快照重算，避免累计缩放。
 */
final class ReferenceUiScale {
    private final View root;
    private final List<Item> items = new ArrayList<>();
    private final float density;
    private final float scaledDensity;
    private float scale;

    private ReferenceUiScale(View root, boolean followRootSize) {
        this.root = root;
        density = root.getResources().getDisplayMetrics().density;
        scaledDensity = root.getResources().getDisplayMetrics().scaledDensity;
        collect(root);
        apply(root.getResources().getDisplayMetrics().widthPixels,
                root.getResources().getDisplayMetrics().heightPixels);
        if (followRootSize) root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) apply(r - l, b - t);
        });
    }

    /** 完成inflate后、动态字幕加入前绑定；同一树只调用一次。 */
    static ReferenceUiScale bind(View root) { return new ReferenceUiScale(root, true); }

    /** 弹层宽度本身不是屏幕宽度，用外部屏幕基准计算一次，避免二次缩小弹层。 */
    static ReferenceUiScale bindDialog(View root) { return new ReferenceUiScale(root, false); }

    /** 把后续动态条目的设计尺寸转换为该页当前像素。 */
    int px(float designUnits) { return Math.round(designUnits * scale); }
    float textSp(float designUnits) { return designUnits * scale / scaledDensity; }

    /** 登录弹层等未持有页面实例的动态控件使用同一屏幕基准。 */
    static int px(Context context, float units) {
        var metrics = context.getResources().getDisplayMetrics();
        return Math.round(units * Math.min(metrics.widthPixels / 1920f, metrics.heightPixels / 1080f));
    }

    private void collect(View view) {
        items.add(new Item(view));
        if (view instanceof ViewGroup group)
            for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i));
    }

    private void apply(int width, int height) {
        if (width <= 0 || height <= 0) return;
        scale = Math.min(width / 1920f, height / 1080f);
        float factor = scale / density;
        for (Item item : items) item.apply(factor, scale / scaledDensity);
    }

    /** 首次布局事实仅用于视觉尺寸，不保存账号、会议状态或字幕内容。 */
    private static final class Item {
        final View view;
        final int width, height, left, top, right, bottom, start, end;
        final int padLeft, padTop, padRight, padBottom, minimumWidth, minimumHeight;
        final float textSize, cornerRadius;
        Item(View view) {
            this.view = view;
            var params = view.getLayoutParams();
            width = params == null ? -1 : params.width;
            height = params == null ? -1 : params.height;
            var margins = params instanceof ViewGroup.MarginLayoutParams m ? m : null;
            left = margins == null ? 0 : margins.leftMargin;
            top = margins == null ? 0 : margins.topMargin;
            right = margins == null ? 0 : margins.rightMargin;
            bottom = margins == null ? 0 : margins.bottomMargin;
            start = margins == null ? 0 : margins.getMarginStart();
            end = margins == null ? 0 : margins.getMarginEnd();
            padLeft = view.getPaddingLeft(); padTop = view.getPaddingTop();
            padRight = view.getPaddingRight(); padBottom = view.getPaddingBottom();
            minimumWidth = view.getMinimumWidth(); minimumHeight = view.getMinimumHeight();
            textSize = view instanceof TextView text ? text.getTextSize() : 0;
            cornerRadius = view.getBackground() instanceof GradientDrawable shape ? shape.getCornerRadius() : 0;
        }
        void apply(float factor, float textFactor) {
            var params = view.getLayoutParams();
            if (params != null) {
                params.width = width > 0 ? Math.round(width * factor) : width;
                params.height = height > 0 ? Math.round(height * factor) : height;
                if (params instanceof ViewGroup.MarginLayoutParams m) {
                    m.setMargins(Math.round(left * factor), Math.round(top * factor),
                            Math.round(right * factor), Math.round(bottom * factor));
                    m.setMarginStart(Math.round(start * factor)); m.setMarginEnd(Math.round(end * factor));
                }
                view.setLayoutParams(params);
            }
            view.setPadding(Math.round(padLeft * factor), Math.round(padTop * factor),
                    Math.round(padRight * factor), Math.round(padBottom * factor));
            view.setMinimumWidth(Math.round(minimumWidth * factor));
            view.setMinimumHeight(Math.round(minimumHeight * factor));
            if (view instanceof TextView text) text.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize * textFactor);
            if (cornerRadius > 0 && view.getBackground() instanceof GradientDrawable shape)
                ((GradientDrawable)shape.mutate()).setCornerRadius(cornerRadius * factor);
        }
    }
}
