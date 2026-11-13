package com.carevoice.documentation.infrastructure.ai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The text of the field [key] when it is a JSON primitive (a string, number or boolean). Null when the field is
 * missing, JSON null, an object or an array. The provider response parsers use it to read single fields safely.
 */
internal fun JsonObject.primitiveText(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
