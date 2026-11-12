package luminaworld.luminahud.web

import com.google.gson.Gson
import com.google.gson.JsonObject
import io.javalin.Javalin
import io.javalin.http.Context
import io.javalin.http.HttpStatus
import io.javalin.json.JsonMapper
import luminaworld.luminahud.LuminaHUDPlugin
import luminaworld.luminahud.config.HudDefinition
import luminaworld.luminahud.config.HudLayer
import luminaworld.luminahud.config.HudTextLayer
import luminaworld.luminahud.generator.PackBuildResult
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import java.io.File
import java.io.InputStream
import java.lang.reflect.Type
import java.util.concurrent.CompletableFuture
import java.util.function.Supplier

/**
 * Embedded Web Server ด้วย Javalin สำหรับ Web Editor, REST APIs, และการแจกจ่าย Resource Pack
 */
class WebServer(private val plugin: LuminaHUDPlugin) {

    private val gson = Gson()
    private var app: Javalin? = null
    var isRunning: Boolean = false
        private set

    fun start() {
        if (!plugin.configManager.webEnabled) {
            plugin.logger.info("[Web Editor] ระบบปิดการใช้งานตามคอนฟิก")
            return
        }

        try {
            val port = plugin.configManager.webPort
            val host = plugin.configManager.webHost

            app = Javalin.create { config ->
                // กำหนด JSON Mapper ด้วย Gson ของเซิร์ฟเวอร์ Paper
                config.jsonMapper(object : JsonMapper {
                    override fun toJsonString(obj: Any, type: Type): String {
                        return gson.toJson(obj, type)
                    }

                    override fun toJsonStream(obj: Any, type: Type): InputStream {
                        return gson.toJson(obj, type).byteInputStream(Charsets.UTF_8)
                    }

                    override fun <T : Any> fromJsonString(json: String, targetType: Type): T {
                        return gson.fromJson(json, targetType)
                    }

                    override fun <T : Any> fromJsonStream(json: InputStream, targetType: Type): T {
                        return json.bufferedReader(Charsets.UTF_8).use { reader ->
                            gson.fromJson(reader, targetType)
                        }
                    }
                })

                // อนุญาต CORS สำหรับเรียก API ข้ามโดเมน
                config.bundledPlugins.enableCors { cors ->
                    cors.addRule { rule ->
                        rule.anyHost()
                    }
                }
                config.showJavalinBanner = false
            }.apply {
                // ตัวกรองความปลอดภัย Token Auth สำหรับ /api/*
                before("/api/*") { ctx ->
                    if (!isAuthorized(ctx)) {
                        ctx.status(HttpStatus.UNAUTHORIZED).json(mapOf("error" to "Unauthorized: Invalid or missing token"))
                    }
                }

                // หน้าหลัก: LuminaHUD Web Studio Single-Page Application
                get("/") { ctx ->
                    ctx.html(loadStudioHtml())
                }

                // Endpoint ดาวน์โหลดไฟล์ Resource Pack สำหรับโหมด LOCAL_HOST
                get("/pack/download") { ctx ->
                    val zipFile = plugin.configManager.packZipFile
                    if (zipFile.exists() && zipFile.length() > 0L) {
                        ctx.header("Content-Disposition", "attachment; filename=\"${zipFile.name}\"")
                        ctx.contentType("application/zip")
                        ctx.result(zipFile.inputStream())
                    } else {
                        ctx.status(HttpStatus.NOT_FOUND).result("Resource Pack file not found")
                    }
                }

                // API: ตรวจสอบสถานะระบบ
                get("/api/status") { ctx ->
                    val statusMap = mapOf(
                        "plugin" to plugin.name,
                        "version" to plugin.pluginMeta.version,
                        "registeredGlyphs" to plugin.fontRegistry.totalRegistered,
                        "hudsCount" to loadHudDefinitions().size,
                        "deliveryMode" to plugin.configManager.deliveryMode,
                        "currentPackUrl" to (plugin.packDeliveryManager.currentPackUrl ?: "none"),
                        "autoMergeEnabled" to plugin.configManager.autoMergeEnabled,
                        "packFormat" to plugin.configManager.packFormat,
                        "webPort" to plugin.configManager.webPort
                    )
                    ctx.json(statusMap)
                }

                // API: รายการ HUD ทั้งหมด
                get("/api/huds") { ctx ->
                    ctx.json(loadHudDefinitions())
                }

                // API: ดึงข้อมูล HUD แต่ละตัวตาม ID
                get("/api/hud/{id}") { ctx ->
                    val id = ctx.pathParam("id")
                    val file = File(plugin.configManager.hudsFolder, "$id.yml")
                    val altFile = File(plugin.configManager.hudsFolder, "$id.yaml")
                    val target = when {
                        file.exists() -> file
                        altFile.exists() -> altFile
                        else -> null
                    }

                    if (target != null) {
                        val hud = HudDefinition.fromYaml(target)
                        if (hud != null) {
                            ctx.json(hud)
                            return@get
                        }
                    }
                    ctx.status(HttpStatus.NOT_FOUND).json(mapOf("error" to "HUD '$id' not found"))
                }

                // API: บันทึกข้อมูล HUD และสั่ง Rebuild & Deploy Resource Pack ทันที (Live Sync)
                post("/api/hud/save") { ctx ->
                    try {
                        val json = gson.fromJson(ctx.body(), JsonObject::class.java)
                            ?: throw IllegalArgumentException("Invalid JSON body")

                        val rawId = json.get("id")?.asString?.trim() ?: ""
                        val safeId = rawId.replace(Regex("[^a-zA-Z0-9_-]"), "_").lowercase()
                        if (safeId.isBlank()) {
                            ctx.status(HttpStatus.BAD_REQUEST).json(mapOf("error" to "HUD ID cannot be blank"))
                            return@post
                        }

                        val name = json.get("name")?.asString?.ifBlank { safeId } ?: safeId
                        val enabled = if (json.has("enabled")) json.get("enabled").asBoolean else true
                        val displayType = json.get("displayType")?.asString ?: "ACTIONBAR"
                        val priority = json.get("priority")?.asInt ?: 10
                        val anchor = json.get("anchor")?.asString ?: "BOTTOM_CENTER"

                        val enabledWorlds = if (json.has("enabledWorlds") && json.get("enabledWorlds").isJsonArray) {
                            json.getAsJsonArray("enabledWorlds").map { it.asString }
                        } else {
                            emptyList()
                        }

                        // แยกเลเยอร์กราฟิก / SVG
                        val layersList = mutableListOf<HudLayer>()
                        if (json.has("layers") && json.get("layers").isJsonArray) {
                            val arr = json.getAsJsonArray("layers")
                            for ((idx, el) in arr.withIndex()) {
                                val obj = el.asJsonObject
                                val layerId = obj.get("id")?.asString ?: "${safeId}_layer_$idx"
                                val type = obj.get("type")?.asString ?: "SVG"
                                val file = obj.get("file")?.asString ?: ""
                                val ascent = obj.get("ascent")?.asInt ?: 8
                                val height = obj.get("height")?.asInt ?: 16
                                val xOffset = obj.get("xOffset")?.asInt ?: obj.get("x-offset")?.asInt ?: 0
                                val yOffset = obj.get("yOffset")?.asInt ?: obj.get("y-offset")?.asInt ?: 0
                                val scale = obj.get("scale")?.asDouble ?: 1.0
                                val condition = obj.get("condition")?.asString
                                val svgContent = obj.get("svgContent")?.asString ?: obj.get("svg-content")?.asString

                                layersList.add(
                                    HudLayer(
                                        id = layerId,
                                        type = type,
                                        file = file,
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
                        } else if (json.has("rawSvg") && !json.get("rawSvg").asString.isNullOrBlank()) {
                            // รองรับกรณีส่ง Raw SVG มาโดยตรงจาก Figma SVG Importer
                            val rawSvg = json.get("rawSvg").asString
                            val ascent = json.get("ascent")?.asInt ?: 8
                            val height = json.get("height")?.asInt ?: 16
                            val xOffset = json.get("offsetX")?.asInt ?: 0
                            val yOffset = json.get("offsetY")?.asInt ?: 0
                            val scale = json.get("svgScale")?.asDouble ?: 1.0

                            layersList.add(
                                HudLayer(
                                    id = "${safeId}_base",
                                    type = "SVG",
                                    file = "",
                                    ascent = ascent,
                                    height = height,
                                    xOffset = xOffset,
                                    yOffset = yOffset,
                                    scale = scale,
                                    condition = null,
                                    svgContent = rawSvg
                                )
                            )
                        }

                        // แยกเลเยอร์ข้อความ Dynamic Text & PlaceholderAPI
                        val textLayersList = mutableListOf<HudTextLayer>()
                        if (json.has("textLayers") && json.get("textLayers").isJsonArray) {
                            val arr = json.getAsJsonArray("textLayers")
                            for ((idx, el) in arr.withIndex()) {
                                val obj = el.asJsonObject
                                val textId = obj.get("id")?.asString ?: "${safeId}_text_$idx"
                                val text = obj.get("text")?.asString ?: ""
                                val x = obj.get("x")?.asInt ?: 0
                                val y = obj.get("y")?.asInt ?: 0
                                val align = obj.get("align")?.asString ?: "LEFT"
                                val scale = obj.get("scale")?.asDouble ?: 1.0
                                val color = obj.get("color")?.asString ?: "#FFFFFF"
                                val shadow = if (obj.has("shadow")) obj.get("shadow").asBoolean else true
                                val condition = obj.get("condition")?.asString

                                textLayersList.add(
                                    HudTextLayer(
                                        id = textId,
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
                        }

                        val hudDef = HudDefinition(
                            id = safeId,
                            name = name,
                            enabled = enabled,
                            displayType = displayType,
                            priority = priority,
                            enabledWorlds = enabledWorlds,
                            anchor = anchor,
                            layers = layersList,
                            textLayers = textLayersList
                        )

                        // 1. บันทึกลงไฟล์ plugins/LuminaHUD/huds/<safeId>.yml
                        val targetFile = File(plugin.configManager.hudsFolder, "$safeId.yml")
                        hudDef.saveToYaml(targetFile)
                        plugin.logger.info("[Web Editor] บันทึกไฟล์ HUD เรียบร้อย: ${targetFile.name}")

                        // 2. เรียก PackBuilder สร้างภาพ PNG & Font ทันที (Asynchronous)
                        CompletableFuture.supplyAsync(Supplier<PackBuildResult> {
                            val result = plugin.packBuilder.buildPack()
                            if (result.success && result.zipFile != null) {
                                plugin.packDeliveryManager.processAndDeploy(result.zipFile).join()
                            }
                            result
                        }).thenAccept { buildResult ->
                            // 3. แจ้งเตือนแอดมินในเซิร์ฟเวอร์แบบ Live Reload
                            Bukkit.getScheduler().runTask(plugin, Runnable {
                                for (player in Bukkit.getOnlinePlayers()) {
                                    if (player.hasPermission("luminahud.admin")) {
                                        player.sendMessage(MiniMessage.miniMessage().deserialize(
                                            "<gradient:#C351F5:#58A6FF>[LuminaHUD Studio]</gradient> <green>อัปเดต HUD <yellow>$safeId</yellow> เรียบร้อยแล้ว (Live Reload)</green>"
                                        ))
                                    }
                                }
                            })

                            ctx.json(
                                mapOf(
                                    "success" to true,
                                    "message" to "บันทึก HUD '$safeId' และประมวลผล Resource Pack สำเร็จ!",
                                    "hudId" to safeId,
                                    "durationMs" to buildResult.durationMs,
                                    "layersProcessed" to buildResult.totalLayersProcessed,
                                    "tilesGenerated" to buildResult.totalTilesGenerated,
                                    "packUrl" to (plugin.packDeliveryManager.currentPackUrl ?: "")
                                )
                            )
                        }.exceptionally { ex ->
                            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(mapOf("error" to (ex.message ?: "Unknown build error")))
                            null
                        }
                    } catch (e: Exception) {
                        plugin.logger.severe("[Web Editor] เกิดข้อผิดพลาดขณะบันทึก HUD: ${e.message}")
                        ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(mapOf("error" to (e.message ?: "Unknown error")))
                    }
                }

                // API: สั่งประมวลผลสร้าง Resource Pack แบบกำหนดเอง
                post("/api/pack/generate") { ctx ->
                    CompletableFuture.supplyAsync(Supplier<PackBuildResult> {
                        val result = plugin.packBuilder.buildPack()
                        if (result.success && result.zipFile != null) {
                            plugin.packDeliveryManager.processAndDeploy(result.zipFile)
                        }
                        result
                    }).thenAccept { result: PackBuildResult ->
                        ctx.json(
                            mapOf(
                                "success" to result.success,
                                "message" to result.message,
                                "durationMs" to result.durationMs,
                                "layersProcessed" to result.totalLayersProcessed,
                                "tilesGenerated" to result.totalTilesGenerated,
                                "zipSize" to result.zipSizeBytes,
                                "merged" to result.merged,
                                "packUrl" to (plugin.packDeliveryManager.currentPackUrl ?: "")
                            )
                        )
                    }.exceptionally { ex ->
                        ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(mapOf("error" to (ex.message ?: "Unknown error")))
                        null
                    }
                }

                start(host, port)
            }

            isRunning = true
            plugin.logger.info("[Web Editor] เริ่มต้นทำงานแล้วที่: http://$host:$port (Token: ${plugin.configManager.webSecretToken})")
        } catch (e: Exception) {
            isRunning = false
            plugin.logger.severe("[Web Editor] ไม่สามารถเริ่มการทำงานของ Web Server ได้: ${e.message}")
            if (plugin.configManager.debug) {
                e.printStackTrace()
            }
        }
    }

    fun stop() {
        try {
            app?.stop()
            app = null
            isRunning = false
            plugin.logger.info("[Web Editor] หยุดการทำงานของ Web Server และปล่อยพอร์ตเรียบร้อยแล้ว")
        } catch (e: Exception) {
            plugin.logger.warning("[Web Editor] ข้อผิดพลาดขณะปิด Web Server: ${e.message}")
        }
    }

    private fun isAuthorized(ctx: Context): Boolean {
        val configuredToken = plugin.configManager.webSecretToken
        val authTokenManager = plugin.authTokenManager

        // ค้นหา Token จาก Header หรือ Query Param
        val token = ctx.header("Authorization")?.removePrefix("Bearer ")?.trim()
            ?: ctx.header("X-Lumina-Token")?.trim()
            ?: ctx.queryParam("token")?.trim()
            ?: ""

        // หากในคอนฟิกไม่ได้ตั้ง Secret Token ไว้ ถือว่าอนุญาต
        if (configuredToken.isBlank()) return true

        // ตรวจสอบกับ Token ถาวรใน config หรือ One-Time Token ชั่วคราว
        if (token.isNotBlank()) {
            if (token == configuredToken) return true
            if (authTokenManager.isValid(token)) return true
        }

        return false
    }

    private fun loadHudDefinitions(): List<HudDefinition> {
        val files = plugin.configManager.hudsFolder.listFiles()?.filter { f ->
            f.isFile && (f.extension.equals("yml", ignoreCase = true) || f.extension.equals("yaml", ignoreCase = true))
        } ?: emptyList()
        return files.mapNotNull { HudDefinition.fromYaml(it) }
    }

    /**
     * โหลดหน้าเว็บ Web Studio จาก resources หรือคืนค่าหน้า Dashboard สำรอง
     */
    private fun loadStudioHtml(): String {
        val stream = javaClass.getResourceAsStream("/web/studio.html")
        return if (stream != null) {
            stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } else {
            renderFallbackDashboardHtml()
        }
    }

    private fun renderFallbackDashboardHtml(): String {
        val token = plugin.configManager.webSecretToken
        val port = plugin.configManager.webPort
        return """
            <!DOCTYPE html>
            <html lang="th">
            <head>
                <meta charset="UTF-8">
                <title>LuminaHUD Web Studio</title>
                <style>body { background: #02020A; color: white; font-family: sans-serif; display: flex; justify-content: center; align-items: center; height: 100vh; margin: 0; }</style>
            </head>
            <body>
                <div style="text-align: center;">
                    <h1 style="color: #C351F5;">✨ LuminaHUD Web Studio</h1>
                    <p style="color: #a0aec0;">Server Port: $port | Token: $token</p>
                    <p>กำลังเตรียมความพร้อมของทรัพยากรหน้าเว็บ...</p>
                </div>
            </body>
            </html>
        """.trimIndent()
    }
}
