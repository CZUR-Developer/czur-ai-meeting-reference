package com.czur.aimeeting.client.display;

import static org.junit.Assert.*;
import com.czur.aimeeting.model.AudioDirectionSample;
import com.czur.aimeeting.model.SegmentStartedEvent;
import com.czur.aimeeting.model.TranscriptEvent;
import com.czur.aimeeting.model.TranslationEvent;
import org.junit.Test;

/** 国内正常显示链：会前语言/方向、句开始、迟到语种与停止；没有网络或Android依赖。 */
public final class DomesticDisplayTest {
    @Test public void latestVisibleTranscriptIdKeepsRevisionArrivalInsteadOfRowInsertionOrder() {
        MeetingDisplayState display = new MeetingDisplayState();
        display.start(0);
        display.apply(event("s1", 1, "zh-CN", "第一句", false), 0);
        display.apply(event("s2", 1, "zh-CN", "第二句", false), 1);
        MeetingDisplaySnapshot older = display.snapshot();
        display.apply(event("s1", 2, "zh-CN", "第一句迟到修订", true), 2);
        assertEquals("s1", display.snapshot().latestTranscriptSegmentId());
        assertEquals("s2", older.latestTranscriptSegmentId());
        assertEquals("s2", display.snapshot().rows().get(1).segmentId());
    }

    @Test public void sentenceBeginKeepsItsDirectionWhileNextSpeakerMoves() {
        MeetingDisplayState display = bilingual();
        display.updateAudioDirection(direction(220, AudioDirectionSample.Status.AVAILABLE));
        display.onSegmentStarted(new SegmentStartedEvent("first", 0));
        display.updateAudioDirection(direction(300, AudioDirectionSample.Status.AVAILABLE));
        display.apply(event("first", 1, "en-US", "Hello", false), 100);
        assertEquals(1, display.route("first").side());
        display.updateAudioDirection(direction(Float.NaN, AudioDirectionSample.Status.NO_DIRECTION));
        display.apply(event("second", 1, "zh-CN", "你好", false), 200);
        assertEquals(2, display.route("second").side());
        display.updateAudioDirection(direction(Float.NaN, AudioDirectionSample.Status.UNSUPPORTED));
        display.apply(event("third", 1, "zh-CN", "下一句", false), 300);
        assertEquals(DisplayRouter.Basis.MODEL_LANGUAGE, display.route("third").basis());
        assertEquals(1, display.route("third").side());
    }

    @Test public void latestSnapshotExpiresExactlyAtDeadlineAndLateLanguageDoesNotMoveIt() {
        MeetingDisplayState display = bilingual();
        display.updateStableDirection(90);
        display.apply(event("one", 1, "und", "Hello", false), 100);
        display.apply(event("one", 2, "und", "Hello everyone", false), 1000);
        assertEquals(1600, display.nextLanguageDeadlineMs().getAsLong());
        assertTrue(display.segments().isEmpty());
        assertFalse(display.releaseExpiredLanguages(1599));
        assertTrue(display.releaseExpiredLanguages(1600));
        assertEquals("Hello everyone", display.latestBilingualRow().right());
        assertEquals("und", display.segment("one").language());
        MeetingDisplaySnapshot oldSnapshot = display.snapshot();
        display.apply(event("one", 3, "zh-CN", "修订事实", true), 1700);
        assertEquals(2, display.route("one").side());
        assertEquals("zh-CN", display.snapshot().rows().get(0).transcript().language());
        assertEquals("Hello everyone", oldSnapshot.rows().get(0).transcript().text());
        assertEquals("und", oldSnapshot.rows().get(0).transcript().language());
        assertTrue(display.nextLanguageDeadlineMs().isEmpty());
    }

    @Test public void trustedLanguageBeforeDeadlineReleasesOnlyNewestSnapshot() {
        MeetingDisplayState display = bilingual();
        display.updateStableDirection(270);
        display.apply(event("one", 1, "und", "数字123", false), 100);
        display.apply(event("one", 2, "en-US", "123 people", false), 1599);
        assertEquals(2, display.route("one").side());
        assertEquals("123 people", display.latestBilingualRow().right());
        assertEquals(1, display.segments().size());
        assertTrue(display.nextLanguageDeadlineMs().isEmpty());
    }

    @Test public void lateProviderAtDeadlineKeepsFallbackOfPreviousLatestText() {
        MeetingDisplayState display = bilingual();
        display.updateStableDirection(270);
        display.apply(event("one", 1, "und", "你好", false), 0);
        display.apply(event("one", 2, "en-US", "Hello", true), 1500);
        assertEquals(1, display.route("one").side());
        assertEquals("en-US", display.segment("one").language());
        assertEquals("Hello", display.latestBilingualRow().left());
    }

    @Test public void stopFlushesLatestPendingTextAndDoesNotStartAnotherWait() {
        MeetingDisplayState display = bilingual();
        display.updateStableDirection(270);
        display.apply(event("one", 1, "und", "12", false), 100);
        display.apply(event("one", 2, "und", "123", false), 200);
        assertTrue(display.flushPendingLanguages());
        assertEquals("123", display.latestBilingualRow().left());
        assertEquals("und", display.segment("one").language());
        assertTrue(display.nextLanguageDeadlineMs().isEmpty());
        display.apply(event("two", 1, "und", "末尾文字", true), 250);
        assertEquals(2, display.segments().size());
        assertTrue(display.nextLanguageDeadlineMs().isEmpty());
    }

    @Test public void translationCanReleasePendingSourceWithoutChangingRawLanguage() {
        MeetingDisplayState display = bilingual();
        display.updateStableDirection(90);
        display.apply(event("one", 1, "und", "123", false), 100);
        display.apply(new TranslationEvent("tr", "one", 1, "en-US", "zh-CN", "123人", false), 200);
        assertEquals(2, display.route("one").side());
        assertEquals("und", display.segment("one").language());
        assertEquals("123人", display.latestBilingualRow().left());
    }

    @Test public void definiteBoundaryAndMultipleConfiguredLanguagesMatchDomesticRules() {
        for (float angle : new float[]{180, 260}) {
            DisplayRouter router = new DisplayRouter("zh-CN", "en-US");
            router.updateStableAngle(angle);
            assertEquals(1, router.route("one", "en-US", "Hello").side());
        }
        for (float angle : new float[]{280, 360}) {
            DisplayRouter router = new DisplayRouter("zh-CN", "en-US");
            router.updateStableAngle(angle);
            assertEquals(2, router.route("one", "zh-CN", "你好").side());
        }
        assertEquals("ja-JP", BilingualLanguageResolver.resolve("こんにちは", "zh-CN", "ja-JP").language());
        assertEquals("ko-KR", BilingualLanguageResolver.resolve("안녕하세요", "en-US", "ko-KR").language());
        assertEquals("fr-FR", BilingualLanguageResolver.resolve("bonjour merci", "en-US", "fr-FR").language());
        assertEquals("de-DE", BilingualLanguageResolver.resolve("danke bitte", "en-US", "de-DE").language());
        assertEquals("ru-RU", BilingualLanguageResolver.resolve("это как мы", "en-US", "ru-RU").language());
        assertEquals("en-US", BilingualLanguageResolver.resolve("123", "en-US", "fr-FR").language());
    }

    private static MeetingDisplayState bilingual() {
        MeetingDisplayState display = new MeetingDisplayState();
        display.start(0, true, "zh-CN", "en-US");
        display.setWaitForModelLanguage(true);
        return display;
    }
    private static TranscriptEvent event(String id, int revision, String language, String text, boolean end) {
        return new TranscriptEvent(id, revision, 0, 100, language, text, end);
    }
    private static AudioDirectionSample direction(float angle, AudioDirectionSample.Status status) {
        return new AudioDirectionSample(angle, angle, 0, status);
    }
}
