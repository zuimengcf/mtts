package com.mtts.app.service

import com.mtts.app.engine.EngineRegistry
import com.mtts.app.util.TtsVoicesCache
import java.util.IllformedLocaleException
import java.util.Locale
import java.util.MissingResourceException

/** Android TTS 的传统语言代码视图；协议使用 ISO-3 language/country。 */
internal data class TtsLocaleInfo(
    val locale: Locale,
    val languageIso3: String,
    val countryIso3: String,
    val variant: String
) {
    val id: String
        get() = "$languageIso3|$countryIso3|$variant"

    /** CHECK_TTS_DATA 协议要求的 lang-COUNTRY-variant 字符串。 */
    val checkDataName: String
        get() = buildList {
            add(languageIso3)
            if (countryIso3.isNotEmpty()) add(countryIso3)
            if (countryIso3.isNotEmpty() && variant.isNotEmpty()) add(variant)
        }.joinToString("-")
}

internal enum class TtsLanguageAvailability {
    LANGUAGE,
    COUNTRY,
    COUNTRY_VARIANT,
    UNSUPPORTED
}

/** 来自已注册引擎实际音色缓存的一条系统 TTS voice 记录。 */
internal data class TtsSystemVoice(
    val engineCode: String,
    val shortName: String,
    val locale: TtsLocaleInfo
)

/** Locale 归一及 Android 语言状态判断；不推测或补造任何音色。 */
internal object TtsLocaleSupport {
    fun parseVoiceLocale(languageTag: String): TtsLocaleInfo? {
        if (languageTag.isBlank()) return null
        val locale = try {
            Locale.Builder().setLanguageTag(languageTag.trim()).build()
        } catch (_: IllformedLocaleException) {
            return null
        }
        if (locale.language.isBlank() || locale.language.equals("und", ignoreCase = true)) return null

        val language = toIso3Language(locale.language) ?: return null
        val country = if (locale.country.isBlank()) "" else toIso3Country(locale.country) ?: return null
        return TtsLocaleInfo(locale, language, country, locale.variant)
    }

    /** 只返回有效、去重后的音色 locale；输入应来自实际可用的音色记录。 */
    fun availableVoiceLocales(languageTags: Iterable<String>): List<TtsLocaleInfo> =
        languageTags.mapNotNull(::parseVoiceLocale).distinctBy { it.id }

    /** 生成 CHECK_TTS_DATA 的 availableVoices 值，不包含无 locale 或无效 locale 的条目。 */
    fun availableVoiceData(languageTags: Iterable<String>): List<String> =
        availableVoiceLocales(languageTags).map { it.checkDataName }

    fun availability(
        lang: String,
        country: String?,
        variant: String?,
        available: List<TtsLocaleInfo>
    ): TtsLanguageAvailability {
        val requested = requestLocale(lang, country, variant) ?: return TtsLanguageAvailability.UNSUPPORTED
        val sameLanguage = available.filter { it.languageIso3 == requested.languageIso3 }
        if (sameLanguage.isEmpty()) return TtsLanguageAvailability.UNSUPPORTED
        if (requested.countryIso3.isEmpty()) {
            return if (requested.variant.isEmpty()) TtsLanguageAvailability.LANGUAGE
            else TtsLanguageAvailability.UNSUPPORTED
        }

        val sameCountry = sameLanguage.filter { it.countryIso3 == requested.countryIso3 }
        if (sameCountry.isEmpty()) return TtsLanguageAvailability.LANGUAGE
        if (requested.variant.isEmpty()) return TtsLanguageAvailability.COUNTRY
        return if (sameCountry.any { it.variant.equals(requested.variant, ignoreCase = true) }) {
            TtsLanguageAvailability.COUNTRY_VARIANT
        } else {
            TtsLanguageAvailability.COUNTRY
        }
    }

    /** 按请求地区优先、再按语言回退，且绝不跨语言选择默认 voice。 */
    fun bestMatch(
        lang: String,
        country: String?,
        variant: String?,
        available: List<TtsLocaleInfo>
    ): TtsLocaleInfo? {
        val requested = requestLocale(lang, country, variant) ?: return null
        val candidates = available.filter { it.languageIso3 == requested.languageIso3 }
        return candidates.minByOrNull { candidate ->
            when {
                requested.countryIso3.isNotEmpty() &&
                    candidate.countryIso3 == requested.countryIso3 &&
                    candidate.variant.equals(requested.variant, ignoreCase = true) -> 0
                requested.countryIso3.isNotEmpty() && candidate.countryIso3 == requested.countryIso3 -> 1
                requested.countryIso3.isEmpty() && candidate.countryIso3.isEmpty() -> 0
                requested.countryIso3.isEmpty() -> 1
                else -> 2
            }
        }
    }

    private fun requestLocale(lang: String, country: String?, variant: String?): TtsLocaleInfo? {
        val languageIso3 = toIso3Language(lang) ?: return null
        val rawCountry = country.orEmpty().trim()
        val countryIso3 = if (rawCountry.isEmpty()) "" else toIso3Country(rawCountry) ?: return null
        return TtsLocaleInfo(
            locale = Locale(lang.trim(), rawCountry, variant.orEmpty().trim()),
            languageIso3 = languageIso3,
            countryIso3 = countryIso3,
            variant = variant.orEmpty().trim()
        )
    }

    private fun toIso3Language(language: String): String? {
        val value = language.trim().lowercase(Locale.ROOT)
        if (value.length == 3) return value
        if (value.length != 2) return null
        return try {
            Locale(value).getISO3Language().takeIf { it.length == 3 }
        } catch (_: MissingResourceException) {
            null
        }
    }

    private fun toIso3Country(country: String): String? {
        val value = country.trim().uppercase(Locale.ROOT)
        if (value.length == 3) return value
        if (value.length != 2) return null
        return try {
            Locale("", value).getISO3Country().takeIf { it.length == 3 }
        } catch (_: MissingResourceException) {
            null
        }
    }
}

/** 唯一系统清单来源：EngineRegistry 中已注册引擎的实际 voice cache。 */
internal object TtsVoiceInventory {
    fun actualVoices(): List<TtsSystemVoice> = EngineRegistry.all().flatMap { engine ->
        TtsVoicesCache.get(engine).mapNotNull { voice ->
            TtsLocaleSupport.parseVoiceLocale(voice.locale)?.let { locale ->
                TtsSystemVoice(engine.code, voice.shortName, locale)
            }
        }
    }

    fun actualLocaleData(): ArrayList<String> = ArrayList(
        actualVoices().map { it.locale.checkDataName }.distinct()
    )
}
