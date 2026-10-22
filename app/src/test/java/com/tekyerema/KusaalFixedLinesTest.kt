package com.tekyerema

import com.tekyerema.core.config.AppConfig
import com.tekyerema.language.ReplyLocalizer
import com.tekyerema.language.TranslationProvider
import com.tekyerema.voice.Lang
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class KusaalFixedLinesTest {
    @Test fun aKusaalReplyNeverGetsTheFixedTwiLine() = runBlocking<Unit> {
        AppConfig.loadFrom(listOf("locales.json" to """{"locales":{"twFixed":{"Cancelled. Nothing was sent.":"Magyae. Mansoma hwee."}}}"""))
        try {
            val none = object : TranslationProvider {
                override val name = "none"
                override suspend fun translate(text: String, from: String, to: String): String? = null
            }
            assertNotEquals("Magyae. Mansoma hwee.", ReplyLocalizer(none, target = Lang.KUS).toTwi("Cancelled. Nothing was sent."))
            assertEquals("Magyae. Mansoma hwee.", ReplyLocalizer(none).toTwi("Cancelled. Nothing was sent."))
        } finally { AppConfig.loadFrom(emptyList()) }
    }
}
