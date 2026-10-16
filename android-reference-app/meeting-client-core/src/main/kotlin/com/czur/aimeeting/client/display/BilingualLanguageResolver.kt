package com.czur.aimeeting.client.display

import java.util.Locale
import java.util.regex.Pattern

/** 国内多语种 A/B 确定性显示兜底，同分选 A；只定显示侧，不替换标准事件真实语种。 */
class BilingualLanguageResolver private constructor() {
    data class Resolution(private val language: String, private val scoreA: Long, private val scoreB: Long,
        private val reason: String) {
        fun language(): String = language
        fun scoreA(): Long = scoreA
        fun scoreB(): Long = scoreB
        fun reason(): String = reason
    }
    companion object {
        private val WORDS = Pattern.compile("[\\p{L}]+(?:['’][\\p{L}]+)?")
        private val ENGLISH = words("a an and are as at be been but by can could " +
            "did do does for from had has have he her his how i if in is it its me my not of on or our " +
            "she so that the their them there they this to was we were what when where which who will with would you your")
        private val FRENCH = words("au aux avec ce ces cette comme dans de des du elle " +
            "en est et être il ils je la le les leur mais ne nous on ou où pas pour que qui se son sur tout tu " +
            "un une vous bonjour merci")
        private val GERMAN = words("aber als auch auf aus bei das der die ein eine einer " +
            "eines er es für haben ich im in ist kein mit nicht oder sie sind so und von war was werden wie wir zu " +
            "zum zur danke bitte")
        private val RUSSIAN = words("и в не на я что с по это как мы вы")
        /** 数字、标点及未知配置同分稳定选 A。 */
        @JvmStatic fun resolve(text: String?, languageA: String, languageB: String): Resolution {
            val features = Features(text ?: "")
            val a = normalize(languageA)
            val b = normalize(languageB)
            val scoreA = score(a, features)
            val scoreB = score(b, features)
            return Resolution(if (scoreA >= scoreB) languageA else languageB, scoreA, scoreB,
                if (a == b) "same_language_choose_a" else if (scoreA == scoreB) "score_tie_choose_a" else "higher_score")
        }
        /** 原语种别名，仅比较用途，不改事件。 */
        @JvmStatic fun normalize(raw: String?): String {
            val value = raw?.trim()?.lowercase(Locale.ROOT)?.replace('_', '-') ?: ""
            return when (value) {
                "", "auto", "multilingual", "und", "mul" -> ""
                "cn", "zh", "zh-cn", "zh-hans", "zh-hans-cn", "tw", "zh-tw", "zh-hant", "zh-hant-tw", "chinese", "mandarin" -> "cn"
                "en", "en-us", "en-gb", "english" -> "en"
                "ja", "jp", "ja-jp", "japanese" -> "jp"
                "ko", "kr", "ko-kr", "korean" -> "kr"
                "fr", "fr-fr", "french" -> "fr"
                "de", "de-de", "german" -> "de"
                "ru", "ru-ru", "russian" -> "ru"
                else -> value.split('-', limit = 2)[0]
            }
        }
        private fun score(language: String, f: Features): Long = when (language) {
            "cn" -> f.han * 12L + count(f.text, "的了吗是我你他她它在和有不这那为与就也很") * 18L
            "jp" -> f.kana * 18L + f.han * 4L + count(f.text, "はがをにでとのもへやですますしたない") * 24L
            "kr" -> f.hangul * 18L
            "ru" -> f.cyrillic * 18L + f.words.count(RUSSIAN::contains) * 24L
            "en" -> latin(f, ENGLISH, listOf("ing", "tion", "ed", "ly", "ness", "ment")) + matches("['’](?:t|re|ve|ll|d|m|s)\\b", f.lower) * 18L
            "fr" -> latin(f, FRENCH, listOf("ique", "tion", "ment", "euse", "eux", "ais", "ait", "aux")) +
                count(f.text, "ÀÂÆÇÉÈÊËÎÏÔŒÙÛÜŸàâæçéèêëîïôœùûüÿ") * 24L + matches("(?:^|\\s)[cdjlmnstqu]['’]", f.lower) * 18L
            "de" -> latin(f, GERMAN, listOf("sch", "ung", "lich", "keit", "heit", "chen", "schaft")) + count(f.text, "ÄÖÜäöüß") * 24L
            else -> 0L
        }
        private fun latin(f: Features, profile: Set<String>, suffixes: List<String>): Long {
            var score = f.latin * 2L
            for (word in f.words) {
                if (profile.contains(word)) score += 36L
                if (word.length >= 4 && suffixes.any(word::endsWith)) score += 10L
            }
            return score
        }
        private fun count(text: String, characters: String): Long = text.count { characters.indexOf(it) >= 0 }.toLong()
        private fun matches(regex: String, text: String): Long {
            val matcher = Pattern.compile(regex).matcher(text)
            var count = 0L
            while (matcher.find()) count++
            return count
        }
        private fun words(value: String): Set<String> = value.split(' ').toSet()
    }
    private class Features(val text: String) {
        val lower = text.lowercase(Locale.ROOT)
        val words = ArrayList<String>()
        var han = 0
        var kana = 0
        var hangul = 0
        var cyrillic = 0
        var latin = 0
        init {
            val matcher = WORDS.matcher(lower)
            while (matcher.find()) words.add(matcher.group())
            for (c in text) {
                if (c in '\u4e00'..'\u9fff') han++
                if (c in '\u3040'..'\u30ff') kana++
                if (c in '\uac00'..'\ud7af') hangul++
                if (c in '\u0400'..'\u04ff') cyrillic++
                if (c in 'A'..'Z' || c in 'a'..'z' || c in '\u00c0'..'\u024f') latin++
            }
        }
    }
}
