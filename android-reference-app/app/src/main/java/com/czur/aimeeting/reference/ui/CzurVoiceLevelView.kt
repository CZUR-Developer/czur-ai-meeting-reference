package com.czur.aimeeting.reference.ui

import android.content.Context
import android.graphics.drawable.AnimatedImageDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import com.czur.aimeeting.reference.R
import kotlin.math.pow

/** 国内声音显示摘取，输入 SDK 的 0～100 电平，不自行订阅音源或计算 PCM。 */
class CzurVoiceLevelView @JvmOverloads constructor(context: Context, attributes: AttributeSet? = null) : FrameLayout(context, attributes) {
    private val image = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_XY }
    private var level = 0
    init {
        addView(image, LayoutParams(-1, -1, Gravity.CENTER))
        // Android 14 直接加载动画 WebP；nodpi 保持原素材尺寸，振幅只由下面的曲线控制。
        image.setImageResource(R.drawable.czur_overlay_voice)
        image.scaleY = .3f
        contentDescription = "输入声音"
    }
    /** 主线程改变图像高度，保留国内原来的响应曲线，停止或无声输入 0。 */
    fun setLevel(value: Int) {
        level = value.coerceIn(0, 100)
        val normalized = level / 100f
        val height = if (normalized < .1f) .3f else .1f + .9f * (1f - (1f - (normalized - .1f) / .9f).toDouble().pow(4).toFloat())
        image.animate().cancel()
        image.animate().scaleY(height).setDuration(300).start()
    }
    fun getLevel() = level
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        (image.drawable as? AnimatedImageDrawable)?.start()
    }
    override fun onDetachedFromWindow() {
        image.animate().cancel()
        (image.drawable as? AnimatedImageDrawable)?.stop()
        super.onDetachedFromWindow()
    }
}
