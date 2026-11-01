package com.ornek.altyazi

import com.google.mlkit.nl.translate.TranslateLanguage

data class Lang(val name: String, val speechTag: String, val mlKitCode: String)

object Languages {
    val sources = listOf(
        Lang("İngilizce", "en-US", TranslateLanguage.ENGLISH),
        Lang("Almanca", "de-DE", TranslateLanguage.GERMAN),
        Lang("Fransızca", "fr-FR", TranslateLanguage.FRENCH),
        Lang("İspanyolca", "es-ES", TranslateLanguage.SPANISH),
        Lang("İtalyanca", "it-IT", TranslateLanguage.ITALIAN),
        Lang("Portekizce", "pt-BR", TranslateLanguage.PORTUGUESE),
        Lang("Rusça", "ru-RU", TranslateLanguage.RUSSIAN),
        Lang("Arapça", "ar-SA", TranslateLanguage.ARABIC),
        Lang("Japonca", "ja-JP", TranslateLanguage.JAPANESE),
        Lang("Korece", "ko-KR", TranslateLanguage.KOREAN),
        Lang("Çince", "zh-CN", TranslateLanguage.CHINESE),
    )
}
