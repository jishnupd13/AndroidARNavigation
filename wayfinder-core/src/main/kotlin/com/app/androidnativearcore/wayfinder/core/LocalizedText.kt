package com.app.androidnativearcore.wayfinder.core

import java.util.Locale
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Text in several languages, stored as `{"en": "Main Entrance", "ar": "المدخل الرئيسي"}`. */
@Serializable(with = LocalizedTextSerializer::class)
data class LocalizedText(val values: Map<String, String>) {
    constructor(english: String) : this(mapOf("en" to english))

    /**
     * The best match for the given language tags (e.g. `["ar-QA", "en"]`), falling back to English,
     * then to any available value.
     */
    fun resolved(preferredLanguages: List<String> = listOf(Locale.getDefault().toLanguageTag())): String {
        for (language in preferredLanguages) {
            values[language]?.let { return it }
            // "ar-QA" -> "ar"
            language.split('-', '_').firstOrNull()?.let { base -> values[base]?.let { return it } }
        }
        return values["en"] ?: values.values.sorted().firstOrNull() ?: ""
    }
}

object LocalizedTextSerializer : KSerializer<LocalizedText> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("LocalizedText")

    override fun deserialize(decoder: Decoder): LocalizedText {
        val element = (decoder as JsonDecoder).decodeJsonElement()
        // A plain string is accepted as English for convenience.
        if (element is JsonPrimitive) return LocalizedText(element.content)
        return LocalizedText(element.jsonObject.mapValues { it.value.jsonPrimitive.contentOrNull ?: "" })
    }

    override fun serialize(encoder: Encoder, value: LocalizedText) {
        (encoder as JsonEncoder).encodeJsonElement(JsonObject(value.values.mapValues { JsonPrimitive(it.value) }))
    }
}
