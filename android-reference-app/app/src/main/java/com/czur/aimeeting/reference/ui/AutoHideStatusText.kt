package com.czur.aimeeting.reference.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.TextView

/**
 * 状态行文本：有内容才占位，内容为空时整行隐藏。
 *
 * <p>状态文案由页面多处异步写入，调用方逐个判断可见性容易漏；把规则放在控件内部，
 * 无论谁写、在登录前后写，空内容都不会留下一块只有背景的胶囊。</p>
 */
class AutoHideStatusText(context: Context, attrs: AttributeSet?) : TextView(context, attrs) {

    init {
        syncVisibility(text)
    }

    override fun onTextChanged(text: CharSequence?, start: Int, lengthBefore: Int, lengthAfter: Int) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)
        syncVisibility(text)
    }

    private fun syncVisibility(text: CharSequence?) {
        visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
    }
}
