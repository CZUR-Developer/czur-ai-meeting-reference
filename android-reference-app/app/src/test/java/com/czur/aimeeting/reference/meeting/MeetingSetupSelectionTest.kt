package com.czur.aimeeting.reference.meeting

import com.czur.aimeeting.client.MeetingCapabilityState
import org.junit.Assert.*
import org.junit.Test

/** 只覆盖会前选择的正常交互；不访问模型、SDK 音源或 Android 存储。 */
class MeetingSetupSelectionTest {
    @Test fun choosingTheOtherSideLanguageSwapsThePreviousPairInEitherDirection() {
        val selection = MeetingSetupSelection(MeetingSetup.Mode.LIVE_TRANSLATION, "ja-JP", "de-DE", true)
        val expected = selection.copy(sourceLanguage = "de-DE", targetLanguage = "ja-JP")
        assertEquals(expected, selection.selectLanguage(true, "de-DE"))
        assertEquals(expected, selection.selectLanguage(false, "ja-JP"))
        assertEquals(expected, selection.swapLanguages())
        assertEquals("ja-JP", selection.sourceLanguage)
    }

    @Test fun restorationKeepsAvailableSavedLanguagesAndDropsOnlyUnsupportedSelections() {
        val saved = MeetingSetupSelection(MeetingSetup.Mode.LIVE_TRANSLATION, "ja-JP", "de-DE", true)
        assertEquals(saved, saved.availableIn(listOf("fr-FR", "de-DE", "ja-JP"), true))
        val reduced = saved.availableIn(listOf("es-ES", "de-DE"), false)
        assertEquals("es-ES", reduced.sourceLanguage)
        assertEquals("de-DE", reduced.targetLanguage)
        assertFalse(reduced.summaryEnabled)
        assertTrue("能力降级不改写原偏好", saved.summaryEnabled)
    }

    @Test fun fallbackPicksTwoDistinctDeploymentLanguagesWithoutAProviderSpecificDefault() {
        val selection = MeetingSetupSelection(MeetingSetup.Mode.LIVE_TRANSLATION, "old", "old")
            .availableIn(listOf("fr-FR", "it-IT"), true)
        assertEquals("fr-FR", selection.sourceLanguage)
        assertEquals("it-IT", selection.targetLanguage)
        assertEquals("", selection.availableIn(emptyList(), false).sourceLanguage)
    }

    @Test fun theSharedStartRuleRequiresAValidPairOnlyForTranslationAndHonorsSummaryAndBusyState() {
        val capabilities = setOf(MeetingCapabilityState.TRANSCRIPT, MeetingCapabilityState.TRANSLATION,
            MeetingCapabilityState.SUMMARY)
        val languages = listOf("ja-JP", "de-DE")
        val selection = MeetingSetupSelection(MeetingSetup.Mode.LIVE_TRANSLATION, "ja-JP", "de-DE", true)
        assertTrue(selection.canStart(true, false, capabilities, languages, true))
        assertFalse(selection.copy(targetLanguage = "ja-JP").canStart(true, false, capabilities, languages, true))
        assertFalse(selection.canStart(true, true, capabilities, languages, true))
        assertFalse(selection.canStart(false, false, capabilities, languages, true))
        assertFalse(selection.canStart(true, false, capabilities, languages, false))
        assertFalse(selection.canStart(true, false, capabilities - MeetingCapabilityState.SUMMARY, languages, true))
        assertTrue(selection.copy(mode = MeetingSetup.Mode.LIVE_TRANSCRIPT, targetLanguage = "ja-JP")
            .canStart(true, false, capabilities, languages, true))
    }
}
