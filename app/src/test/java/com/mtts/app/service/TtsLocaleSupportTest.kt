package com.mtts.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TtsLocaleSupportTest {
    @Test
    fun checkDataListsOnlyDistinctValidLocalesFromAvailableVoices() {
        val available = TtsLocaleSupport.availableVoiceData(
            listOf("zh-CN", "zh-TW", "zh-CN", "en-US", "", "not_a_locale")
        )

        assertEquals(listOf("zho-CHN", "zho-TWN", "eng-USA"), available)
    }

    @Test
    fun iso3LanguageCallbacksAlignWithBcp47VoiceLocales() {
        val available = TtsLocaleSupport.availableVoiceLocales(listOf("zh-CN", "zh-TW"))

        assertEquals(
            TtsLanguageAvailability.COUNTRY,
            TtsLocaleSupport.availability("zho", "CHN", "", available)
        )
        assertEquals(
            TtsLanguageAvailability.COUNTRY,
            TtsLocaleSupport.availability("zho", "TWN", "", available)
        )
        assertEquals(
            TtsLanguageAvailability.COUNTRY,
            TtsLocaleSupport.availability("zh", "TW", "", available)
        )
        assertEquals(
            TtsLocaleSupport.parseVoiceLocale("zh-TW")?.id,
            TtsLocaleSupport.bestMatch("zho", "TWN", "", available)?.id
        )
    }

    @Test
    fun unavailableRegionIsNotListedAsInstalledAndOnlyFallsBackByLanguage() {
        val available = TtsLocaleSupport.availableVoiceLocales(listOf("zh-CN"))

        assertEquals(listOf("zho-CHN"), available.map { it.checkDataName })
        assertEquals(
            TtsLanguageAvailability.LANGUAGE,
            TtsLocaleSupport.availability("zho", "TWN", "", available)
        )
        assertNull(TtsLocaleSupport.bestMatch("eng", "USA", "", available))
    }
}
