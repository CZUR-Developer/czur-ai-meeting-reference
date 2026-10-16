package com.czur.aimeeting.reference

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableContainer
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/**
 * 以国内 1920×1080 稿为基准，只映射当前页面布局与字号，不修改系统 DisplayMetrics。
 * 每棵 View 树保留首次设计快照，后续重排从原值计算，避免累计缩放。
 */
class ReferenceUiScale private constructor(private val root: View, followRootSize: Boolean) {
    private val items = mutableListOf<Item>()
    private val density = root.resources.displayMetrics.density
    private val scaledDensity = root.resources.displayMetrics.scaledDensity
    private var scale = 0f
    init {
        collect(root)
        apply(root.resources.displayMetrics.widthPixels, root.resources.displayMetrics.heightPixels)
        if (followRootSize) root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, oldR, ob ->
            if (r - l != oldR - ol || b - t != ob - ot) apply(r - l, b - t)
        }
    }
    fun px(designUnits: Float) = Math.round(designUnits * scale)
    fun px(designUnits: Int) = px(designUnits.toFloat())
    fun textSp(designUnits: Float) = designUnits * scale / scaledDensity
    fun textSp(designUnits: Int) = textSp(designUnits.toFloat())
    private fun collect(view: View) {
        items += Item(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) collect(view.getChildAt(i))
    }
    private fun apply(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        scale = minOf(width / 1920f, height / 1080f)
        for (item in items) item.apply(scale / density, scale / scaledDensity)
    }

    /** 首次布局事实只用于视觉尺寸，不保存账号或会议状态。 */
    private class Item(private val view: View) {
        private val initial = view.layoutParams
        private val margins = initial as? ViewGroup.MarginLayoutParams
        private val width = initial?.width ?: -1
        private val height = initial?.height ?: -1
        private val left = margins?.leftMargin ?: 0
        private val top = margins?.topMargin ?: 0
        private val right = margins?.rightMargin ?: 0
        private val bottom = margins?.bottomMargin ?: 0
        private val start = margins?.marginStart ?: 0
        private val end = margins?.marginEnd ?: 0
        private val padLeft = view.paddingLeft
        private val padTop = view.paddingTop
        private val padRight = view.paddingRight
        private val padBottom = view.paddingBottom
        private val minimumWidth = view.minimumWidth
        private val minimumHeight = view.minimumHeight
        private val textSize = (view as? TextView)?.textSize ?: 0f
        private val shapes = mutableListOf<Shape>()
        init { view.background?.mutate()?.let(::collectShapes) }
        private fun collectShapes(drawable: Drawable) {
            if (drawable is GradientDrawable) shapes += Shape(drawable)
            else (drawable.constantState as? DrawableContainer.DrawableContainerState)?.let { states ->
                for (index in 0 until states.childCount) collectShapes(states.getChild(index))
            }
        }
        fun apply(factor: Float, textFactor: Float) {
            view.layoutParams?.let { params ->
                params.width = if (width > 0) Math.round(width * factor) else width
                params.height = if (height > 0) Math.round(height * factor) else height
                if (params is ViewGroup.MarginLayoutParams) {
                    params.setMargins(Math.round(left * factor), Math.round(top * factor), Math.round(right * factor), Math.round(bottom * factor))
                    params.marginStart = Math.round(start * factor)
                    params.marginEnd = Math.round(end * factor)
                }
                view.layoutParams = params
            }
            view.setPadding(Math.round(padLeft * factor), Math.round(padTop * factor), Math.round(padRight * factor), Math.round(padBottom * factor))
            view.minimumWidth = Math.round(minimumWidth * factor)
            view.minimumHeight = Math.round(minimumHeight * factor)
            (view as? TextView)?.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize * textFactor)
            shapes.forEach { it.apply(factor) }
            if (shapes.isNotEmpty()) view.invalidateOutline()
        }
    }
    private class Shape(private val drawable: GradientDrawable) {
        private val radius = drawable.cornerRadius
        private val radii = drawable.cornerRadii
        fun apply(factor: Float) {
            val original = radii
            if (original == null) drawable.cornerRadius = radius * factor
            else drawable.cornerRadii = original.map { it * factor }.toFloatArray()
        }
    }
    companion object {
        @JvmStatic fun bind(root: View) = ReferenceUiScale(root, true)
        @JvmStatic fun bindDialog(root: View) = ReferenceUiScale(root, false)
        @JvmStatic fun px(context: Context, units: Float): Int {
            val metrics = context.resources.displayMetrics
            return Math.round(units * minOf(metrics.widthPixels / 1920f, metrics.heightPixels / 1080f))
        }
        @JvmStatic fun px(context: Context, units: Int) = px(context, units.toFloat())
    }
}
