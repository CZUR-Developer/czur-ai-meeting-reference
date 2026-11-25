package com.czur.aimeeting.client

import java.util.Locale

/**
 * 本场纪要语言列表：主语言固定在前，实时翻译目标语言与主语言主标签不同时追加为第二项。
 * 最多两个；比较只看主标签并忽略大小写和地区码，zh-CN 与 zh-TW、en-US 与 en-GB
 * 视为同一种语言，不重复上报。服务端按该顺序把第一项作为主语言。
 */
internal object SummaryLanguages {
    /** 主语言必填；targetLanguage 为 null 表示本场没有实时翻译。 */
    @JvmStatic fun from(sourceLanguage: String, targetLanguage: String?): List<String> {
        val source = sourceLanguage.trim()
        require(source.isNotEmpty()) { "纪要主语言不能为空" }
        val target = targetLanguage?.trim()?.takeIf { it.isNotEmpty() } ?: return listOf(source)
        return if (primaryTag(source) == primaryTag(target)) listOf(source)
        else listOf(source, target)
    }
    /** 只比较主标签：小写、下划线归一为连字符，并截掉地区／书写系统子标签。 */
    private fun primaryTag(language: String): String =
        language.trim().lowercase(Locale.ROOT).replace('_', '-').substringBefore('-')
}
