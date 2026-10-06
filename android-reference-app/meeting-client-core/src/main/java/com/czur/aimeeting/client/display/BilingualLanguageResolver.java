package com.czur.aimeeting.client.display;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 国内流程的确定性会前 A/B 二选一，仅用于模型语种未及时返回时的显示兜底。
 * 评分规则来自 CloudAsrService 的 BilingualLanguageResolver；同分选 A。
 * 结果只决定显示侧，不能替换标准事件中的真实模型语种。
 */
public final class BilingualLanguageResolver {
    public record Resolution(String language, long scoreA, long scoreB, String reason) { }
    private static final Pattern WORDS = Pattern.compile("[\\p{L}]+(?:['’][\\p{L}]+)?");
    private static final Set<String> ENGLISH = words("a an and are as at be been but by can could "
            + "did do does for from had has have he her his how i if in is it its me my not of on or our "
            + "she so that the their them there they this to was we were what when where which who will with would you your");
    private static final Set<String> FRENCH = words("au aux avec ce ces cette comme dans de des du elle "
            + "en est et être il ils je la le les leur mais ne nous on ou où pas pour que qui se son sur tout tu "
            + "un une vous bonjour merci");
    private static final Set<String> GERMAN = words("aber als auch auf aus bei das der die ein eine einer "
            + "eines er es für haben ich im in ist kein mit nicht oder sie sind so und von war was werden wie wir zu "
            + "zum zur danke bitte");
    private static final Set<String> RUSSIAN = words("и в не на я что с по это как мы вы");
    private BilingualLanguageResolver() { }

    /** 按会前两种语言评分；数字、标点或未知配置的同分结果稳定选择 A。 */
    public static Resolution resolve(String text, String languageA, String languageB) {
        Features features = new Features(text == null ? "" : text);
        String a = normalize(languageA);
        String b = normalize(languageB);
        long scoreA = score(a, features);
        long scoreB = score(b, features);
        return new Resolution(scoreA >= scoreB ? languageA : languageB, scoreA, scoreB,
                a.equals(b) ? "same_language_choose_a"
                        : scoreA == scoreB ? "score_tie_choose_a" : "higher_score");
    }

    /** 和国内流程相同的语种别名；仅用于比较，不改写事件的原始 language。 */
    public static String normalize(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return switch (value) {
            case "", "auto", "multilingual", "und", "mul" -> "";
            case "cn", "zh", "zh-cn", "zh-hans", "zh-hans-cn", "tw", "zh-tw", "zh-hant",
                    "zh-hant-tw", "chinese", "mandarin" -> "cn";
            case "en", "en-us", "en-gb", "english" -> "en";
            case "ja", "jp", "ja-jp", "japanese" -> "jp";
            case "ko", "kr", "ko-kr", "korean" -> "kr";
            case "fr", "fr-fr", "french" -> "fr";
            case "de", "de-de", "german" -> "de";
            case "ru", "ru-ru", "russian" -> "ru";
            default -> value.split("-", 2)[0];
        };
    }

    private static long score(String language, Features f) {
        return switch (language) {
            case "cn" -> f.han * 12L + count(f.text, "的了吗是我你他她它在和有不这那为与就也很") * 18L;
            case "jp" -> f.kana * 18L + f.han * 4L + count(f.text, "はがをにでとのもへやですますしたない") * 24L;
            case "kr" -> f.hangul * 18L;
            case "ru" -> f.cyrillic * 18L + f.words.stream().filter(RUSSIAN::contains).count() * 24L;
            case "en" -> latin(f, ENGLISH, List.of("ing", "tion", "ed", "ly", "ness", "ment"))
                    + matches("['’](?:t|re|ve|ll|d|m|s)\\b", f.lower) * 18L;
            case "fr" -> latin(f, FRENCH, List.of("ique", "tion", "ment", "euse", "eux", "ais", "ait", "aux"))
                    + count(f.text, "ÀÂÆÇÉÈÊËÎÏÔŒÙÛÜŸàâæçéèêëîïôœùûüÿ") * 24L
                    + matches("(?:^|\\s)[cdjlmnstqu]['’]", f.lower) * 18L;
            case "de" -> latin(f, GERMAN, List.of("sch", "ung", "lich", "keit", "heit", "chen", "schaft"))
                    + count(f.text, "ÄÖÜäöüß") * 24L;
            default -> 0L;
        };
    }

    private static long latin(Features f, Set<String> profile, List<String> suffixes) {
        long score = f.latin * 2L;
        for (String word : f.words) {
            if (profile.contains(word)) score += 36L;
            if (word.length() >= 4 && suffixes.stream().anyMatch(word::endsWith)) score += 10L;
        }
        return score;
    }

    private static long count(String text, String characters) {
        return text.chars().filter(c -> characters.indexOf(c) >= 0).count();
    }
    private static long matches(String regex, String text) {
        Matcher matcher = Pattern.compile(regex).matcher(text);
        long count = 0;
        while (matcher.find()) count++;
        return count;
    }
    private static Set<String> words(String value) { return Set.of(value.split(" ")); }

    private static final class Features {
        final String text;
        final String lower;
        final List<String> words = new ArrayList<>();
        int han, kana, hangul, cyrillic, latin;
        Features(String text) {
            this.text = text;
            lower = text.toLowerCase(Locale.ROOT);
            Matcher matcher = WORDS.matcher(lower);
            while (matcher.find()) words.add(matcher.group());
            for (int index = 0; index < text.length(); index++) {
                char c = text.charAt(index);
                if (c >= '\u4e00' && c <= '\u9fff') han++;
                if (c >= '\u3040' && c <= '\u30ff') kana++;
                if (c >= '\uac00' && c <= '\ud7af') hangul++;
                if (c >= '\u0400' && c <= '\u04ff') cyrillic++;
                if (c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '\u00c0' && c <= '\u024f') latin++;
            }
        }
    }
}
