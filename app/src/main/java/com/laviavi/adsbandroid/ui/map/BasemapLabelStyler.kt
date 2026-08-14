package com.laviavi.adsbandroid.ui.map

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.PropertyFactory

/**
 * Discovers a loaded MapLibre style's label layers and overrides their font size/color.
 *
 * Real layer IDs differ per OpenFreeMap style — confirmed live: Liberty and Bright
 * share 23 label-layer IDs like `label_city`, but Dark uses a completely different set
 * (e.g. `place_city`), so this can't be a hardcoded list. Instead it fetches the same
 * style JSON MapLibre already loaded internally and looks for any layer with a
 * `text-field` layout property — the Kotlin SDK doesn't reliably expose reading a
 * loaded native layer's *current* property value, so re-fetching the JSON (once per
 * style switch, not per frame) is the simplest reliable way to find them.
 */
internal object BasemapLabelStyler {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient(CIO)

    suspend fun fetchLabelLayerIds(styleUrl: String): List<String> = runCatching {
        val text = client.get(styleUrl).bodyAsText()
        val layers = json.parseToJsonElement(text).jsonObject["layers"]?.jsonArray ?: return emptyList()
        layers.mapNotNull { element ->
            val obj = element.jsonObject
            val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val hasLabel = obj["layout"]?.jsonObject?.get("text-field") != null
            id.takeIf { hasLabel }
        }
    }.getOrElse { emptyList() }

    /** [colorHex] null leaves each layer's own authored color; [size] `DEFAULT` leaves each layer's own authored size. */
    fun apply(style: Style, labelLayerIds: List<String>, size: MapLabelSize, colorHex: String?) {
        labelLayerIds.forEach { id ->
            val layer = style.getLayer(id) ?: return@forEach
            if (size.px != null) layer.setProperties(PropertyFactory.textSize(size.px))
            if (colorHex != null) layer.setProperties(PropertyFactory.textColor(colorHex))
        }
    }
}
