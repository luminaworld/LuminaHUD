package luminaworld.luminahud.generator

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import luminaworld.luminahud.LuminaHUDPlugin
import luminaworld.luminahud.config.HudDefinition
import luminaworld.luminahud.config.HudLayer
import luminaworld.luminahud.font.FontRegistry
import luminaworld.luminahud.font.NegativeSpaceProvider
import java.awt.image.BufferedImage
import java.io.File
import java.io.FileWriter
import javax.imageio.ImageIO
import kotlin.math.ceil

/**
 * ผลลัพธ์จากการสร้าง Resource Pack
 */
data class PackBuildResult(
    val success: Boolean,
    val durationMs: Long,
    val totalLayersProcessed: Int,
    val totalTilesGenerated: Int,
    val zipFile: File?,
    val zipSizeBytes: Long,
    val merged: Boolean,
    val message: String
)

/**
 * ตัวสร้าง Resource Pack แปลง SVG เป็น Bitmap Font และ Negative Space
 */
class PackBuilder(
    private val plugin: LuminaHUDPlugin,
    val fontRegistry: FontRegistry,
    val negativeSpaceProvider: NegativeSpaceProvider,
    private val packMerger: PackMerger
) {

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val transcoder = SvgTranscoder(antiAliasing = plugin.configManager.antiAliasing)

    /**
     * ดำเนินการสร้าง Resource Pack ทั้งหมด
     */
    @Synchronized
    fun buildPack(): PackBuildResult {
        val startTime = System.currentTimeMillis()
        val config = plugin.configManager
        val generatedPackDir = config.generatedPackFolder

        try {
            // เตรียมไดเรกทอรี
            val namespace = config.fontNamespace
            val texturesDir = File(generatedPackDir, "assets/$namespace/textures/hud")
            val fontDir = File(generatedPackDir, "assets/$namespace/font")
            val mcFontDir = File(generatedPackDir, "assets/minecraft/font")

            texturesDir.mkdirs()
            fontDir.mkdirs()
            mcFontDir.mkdirs()

            // รีเซ็ต Font Registry ตามรหัสเริ่มต้น
            fontRegistry.reset(config.puaStartCode)

            var totalLayers = 0
            var totalTiles = 0

            // อ่าน HUD definitions ทั้งหมดใน huds/
            val hudFiles = config.hudsFolder.listFiles()?.filter { f ->
                f.isFile && (f.extension.equals("yml", ignoreCase = true) || f.extension.equals("yaml", ignoreCase = true))
            } ?: emptyList()
            val hudDefinitions = hudFiles.mapNotNull { HudDefinition.fromYaml(it) }

            for (hud in hudDefinitions) {
                if (!hud.enabled) continue

                for (layer in hud.layers) {
                    val tilesCount = processLayer(layer, texturesDir, namespace, config.maxTileSize)
                    totalLayers++
                    totalTiles += tilesCount
                }
            }

            // 1. สร้างไฟล์ pack.mcmeta
            writePackMcmeta(generatedPackDir, config.packFormat, config.packDescription)

            // 2. สร้างไฟล์ assets/<namespace>/font/default.json และ assets/minecraft/font/default.json
            val fontJson = generateFontJson()
            writeJsonFile(File(fontDir, "default.json"), fontJson)
            writeJsonFile(File(mcFontDir, "default.json"), fontJson)

            // 3. บีบอัดเป็นไฟล์ ZIP
            var zipGenerated = false
            var zipSize = 0L
            val zipFile = config.packZipFile
            if (config.autoZip) {
                zipGenerated = packMerger.createZip(generatedPackDir, zipFile)
                if (zipGenerated && zipFile.exists()) {
                    zipSize = zipFile.length()
                }
            }

            // 4. ผสาน Auto-Merge ถ้าเปิดใช้งาน
            val merged = if (config.autoMergeEnabled) {
                packMerger.autoMerge(generatedPackDir)
            } else {
                false
            }

            val duration = System.currentTimeMillis() - startTime
            val msg = "สร้าง Resource Pack สำเร็จ (ใช้เวลา ${duration}ms, ประมวลผล ${totalLayers} เลเยอร์, สร้าง ${totalTiles} tiles)"
            plugin.logger.info(msg)

            return PackBuildResult(
                success = true,
                durationMs = duration,
                totalLayersProcessed = totalLayers,
                totalTilesGenerated = totalTiles,
                zipFile = if (zipGenerated) zipFile else null,
                zipSizeBytes = zipSize,
                merged = merged,
                message = msg
            )
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            plugin.logger.severe("เกิดข้อผิดพลาดในการสร้าง Resource Pack: ${e.message}")
            if (config.debug) {
                e.printStackTrace()
            }
            return PackBuildResult(
                success = false,
                durationMs = duration,
                totalLayersProcessed = 0,
                totalTilesGenerated = 0,
                zipFile = null,
                zipSizeBytes = 0,
                merged = false,
                message = "ล้มเหลว: ${e.message}"
            )
        }
    }

    /**
     * ประมวลผลเลเยอร์แต่ละชิ้น แปลงเป็น Bitmap และตัด Grid Tiles หากจำเป็น
     */
    private fun processLayer(
        layer: HudLayer,
        outputDir: File,
        namespace: String,
        maxTileSize: Int
    ): Int {
        val image: BufferedImage = when {
            !layer.svgContent.isNullOrBlank() -> {
                transcoder.transcodeSvg(layer.svgContent)
            }
            layer.file.isNotBlank() -> {
                val file = File(plugin.dataFolder, "huds/${layer.file}")
                if (file.exists()) {
                    if (file.extension.equals("svg", ignoreCase = true)) {
                        transcoder.transcodeSvg(file)
                    } else {
                        ImageIO.read(file) ?: return 0
                    }
                } else {
                    plugin.logger.warning("ไม่พบไฟล์ Texture/SVG: ${file.absolutePath}")
                    return 0
                }
            }
            else -> return 0
        }

        val width = image.width
        val height = image.height

        // ตรวจสอบขนาด หากไม่เกิน maxTileSize ให้เซฟเป็นภาพเดี่ยว
        if (width <= maxTileSize && height <= maxTileSize) {
            val fileName = "${layer.id}.png"
            val targetFile = File(outputDir, fileName)
            ImageIO.write(image, "PNG", targetFile)

            fontRegistry.allocateGlyph(
                elementId = layer.id,
                texturePath = "$namespace:hud/$fileName",
                ascent = layer.ascent,
                height = layer.height,
                tileIndex = 0,
                totalTiles = 1
            )
            return 1
        }

        // หากขนาดเกิน ให้ตัดแบ่งเป็น Grid Tiles อัตโนมัติ
        val cols = ceil(width.toDouble() / maxTileSize).toInt()
        val rows = ceil(height.toDouble() / maxTileSize).toInt()
        val totalTiles = cols * rows
        var tilesCount = 0

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val tileX = c * maxTileSize
                val tileY = r * maxTileSize
                val tileW = (width - tileX).coerceAtMost(maxTileSize)
                val tileH = (height - tileY).coerceAtMost(maxTileSize)

                val subImage = image.getSubimage(tileX, tileY, tileW, tileH)
                val fileName = "${layer.id}_c${c}_r${r}.png"
                val targetFile = File(outputDir, fileName)
                ImageIO.write(subImage, "PNG", targetFile)

                val tileIndex = r * cols + c
                // คำนวณ ascent สำหรับแต่ละแถวเพื่อให้แนวระนาบตรงกัน
                val tileAscent = layer.ascent - (r * (layer.height / rows))
                val tileHeight = layer.height / rows

                fontRegistry.allocateGlyph(
                    elementId = layer.id,
                    texturePath = "$namespace:hud/$fileName",
                    ascent = tileAscent,
                    height = tileHeight,
                    tileIndex = tileIndex,
                    totalTiles = totalTiles
                )
                tilesCount++
            }
        }

        return tilesCount
    }

    private fun writePackMcmeta(rootDir: File, packFormat: Int, description: String) {
        val mcmetaFile = File(rootDir, "pack.mcmeta")
        val root = JsonObject()
        val pack = JsonObject()
        pack.addProperty("pack_format", packFormat)
        pack.addProperty("description", description)
        root.add("pack", pack)

        writeJsonFile(mcmetaFile, root)
    }

    private fun generateFontJson(): JsonObject {
        val root = JsonObject()
        val providers = JsonArray()

        // 1. เพิ่ม Negative Space Provider
        if (plugin.configManager.negativeSpaceEnabled) {
            providers.add(negativeSpaceProvider.toJsonProvider())
        }

        // 2. เพิ่ม Bitmap Providers สำหรับแต่ละชิ้นส่วนที่ลงทะเบียนใน FontRegistry
        for (glyph in fontRegistry.getAllGlyphs()) {
            val bitmapObj = JsonObject()
            bitmapObj.addProperty("type", "bitmap")
            bitmapObj.addProperty("file", glyph.texturePath)
            bitmapObj.addProperty("ascent", glyph.ascent)
            bitmapObj.addProperty("height", glyph.height)

            val charsArray = JsonArray()
            charsArray.add(glyph.unicode.toString())
            bitmapObj.add("chars", charsArray)

            providers.add(bitmapObj)
        }

        root.add("providers", providers)
        return root
    }

    private fun writeJsonFile(file: File, json: JsonObject) {
        file.parentFile?.mkdirs()
        FileWriter(file).use { writer ->
            gson.toJson(json, writer)
        }
    }
}
