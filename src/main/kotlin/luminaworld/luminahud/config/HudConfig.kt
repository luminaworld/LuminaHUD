package luminaworld.luminahud.config

import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

/**
 * โครงสร้างข้อมูลของ HUD 1 ชุด
 */
data class HudDefinition(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val displayType: String = "ACTIONBAR",
    val priority: Int = 0,
    val enabledWorlds: List<String> = emptyList(),
    val anchor: String = "BOTTOM_CENTER",
    val layers: List<HudLayer> = emptyList(),
    val textLayers: List<HudTextLayer> = emptyList()
) {
    /**
     * บันทึกข้อมูล HUD ลงไฟล์ YAML ในรูปแบบที่มีโครงสร้างชัดเจน
     */
    fun saveToYaml(file: File) {
        val yaml = YamlConfiguration()
        yaml.set("id", id)
        yaml.set("name", name)
        yaml.set("enabled", enabled)
        yaml.set("display-type", displayType)
        yaml.set("priority", priority)
        yaml.set("anchor", anchor)
        yaml.set("enabled-worlds", enabledWorlds)

        val layersList = layers.map { layer ->
            mutableMapOf<String, Any?>().apply {
                put("id", layer.id)
                put("type", layer.type)
                if (layer.file.isNotBlank()) put("file", layer.file)
                put("ascent", layer.ascent)
                put("height", layer.height)
                put("x-offset", layer.xOffset)
                put("y-offset", layer.yOffset)
                if (layer.scale != 1.0) put("scale", layer.scale)
                if (!layer.condition.isNullOrBlank()) put("condition", layer.condition)
                if (!layer.svgContent.isNullOrBlank()) put("svg-content", layer.svgContent)
            }
        }
        yaml.set("layers", layersList)

        val textLayersList = textLayers.map { tl ->
            mutableMapOf<String, Any?>().apply {
                put("id", tl.id)
                put("text", tl.text)
                put("x", tl.x)
                put("y", tl.y)
                put("align", tl.align)
                put("scale", tl.scale)
                put("color", tl.color)
                put("shadow", tl.shadow)
                if (!tl.condition.isNullOrBlank()) put("condition", tl.condition)
            }
        }
        yaml.set("text-layers", textLayersList)

        file.parentFile?.mkdirs()
        yaml.save(file)
    }

    companion object {
        fun fromYaml(file: File): HudDefinition? {
            return try {
                val yaml = YamlConfiguration.loadConfiguration(file)
                val id = yaml.getString("id", file.nameWithoutExtension) ?: file.nameWithoutExtension
                val name = yaml.getString("name", id) ?: id
                val enabled = yaml.getBoolean("enabled", true)
                val displayType = yaml.getString("display-type", "ACTIONBAR") ?: "ACTIONBAR"
                val priority = yaml.getInt("priority", 0)
                val enabledWorlds = yaml.getStringList("enabled-worlds")
                val anchor = yaml.getString("anchor", "BOTTOM_CENTER") ?: "BOTTOM_CENTER"

                val layerList = mutableListOf<HudLayer>()
                val layersSection = yaml.getMapList("layers")
                for ((index, map) in layersSection.withIndex()) {
                    val layerId = map["id"]?.toString() ?: "layer_$index"
                    val type = map["type"]?.toString() ?: "SVG"
                    val layerFile = map["file"]?.toString() ?: ""
                    val ascent = (map["ascent"] as? Number)?.toInt() ?: 8
                    val height = (map["height"] as? Number)?.toInt() ?: 16
                    val xOffset = (map["x-offset"] as? Number)?.toInt() ?: (map["xOffset"] as? Number)?.toInt() ?: 0
                    val yOffset = (map["y-offset"] as? Number)?.toInt() ?: (map["yOffset"] as? Number)?.toInt() ?: 0
                    val scale = (map["scale"] as? Number)?.toDouble() ?: 1.0
                    val condition = map["condition"]?.toString()
                    val svgContent = map["svg-content"]?.toString() ?: map["svgContent"]?.toString()

                    layerList.add(
                        HudLayer(
                            id = layerId,
                            type = type,
                            file = layerFile,
                            ascent = ascent,
                            height = height,
                            xOffset = xOffset,
                            yOffset = yOffset,
                            scale = scale,
                            condition = condition,
                            svgContent = svgContent
                        )
                    )
                }

                val textLayerList = mutableListOf<HudTextLayer>()
                val textLayersSection = yaml.getMapList("text-layers").ifEmpty { yaml.getMapList("text_layers") }
                for ((index, map) in textLayersSection.withIndex()) {
                    val tId = map["id"]?.toString() ?: "text_$index"
                    val text = map["text"]?.toString() ?: ""
                    val x = (map["x"] as? Number)?.toInt() ?: 0
                    val y = (map["y"] as? Number)?.toInt() ?: 0
                    val align = map["align"]?.toString() ?: "LEFT"
                    val scale = (map["scale"] as? Number)?.toDouble() ?: 1.0
                    val color = map["color"]?.toString() ?: "#FFFFFF"
                    val shadow = (map["shadow"] as? Boolean) ?: true
                    val condition = map["condition"]?.toString()

                    textLayerList.add(
                        HudTextLayer(
                            id = tId,
                            text = text,
                            x = x,
                            y = y,
                            align = align,
                            scale = scale,
                            color = color,
                            shadow = shadow,
                            condition = condition
                        )
                    )
                }

                HudDefinition(
                    id = id,
                    name = name,
                    enabled = enabled,
                    displayType = displayType,
                    priority = priority,
                    enabledWorlds = enabledWorlds,
                    anchor = anchor,
                    layers = layerList,
                    textLayers = textLayerList
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * รายละเอียดเลเยอร์กราฟิกแต่ละชิ้นของ HUD
 */
data class HudLayer(
    val id: String,
    val type: String = "SVG",
    val file: String = "",
    val ascent: Int = 8,
    val height: Int = 16,
    val xOffset: Int = 0,
    val yOffset: Int = 0,
    val scale: Double = 1.0,
    val condition: String? = null,
    val svgContent: String? = null
)

/**
 * รายละเอียดเลเยอร์ข้อความ Dynamic Text & Placeholder บน HUD
 */
data class HudTextLayer(
    val id: String,
    val text: String,
    val x: Int = 0,
    val y: Int = 0,
    val align: String = "LEFT",
    val scale: Double = 1.0,
    val color: String = "#FFFFFF",
    val shadow: Boolean = true,
    val condition: String? = null
)
