package luminaworld.luminahud.config

import luminaworld.luminahud.LuminaHUDPlugin
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.UUID

/**
 * จัดการการอ่านและบันทึกข้อมูลคอนฟิก รวมถึงโครงสร้างไดเรกทอรีของ LuminaHUD
 */
class ConfigManager(private val plugin: LuminaHUDPlugin) {

    val configFile: File = File(plugin.dataFolder, "config.yml")
    val hudsFolder: File = File(plugin.dataFolder, "huds")
    val generatedPackFolder: File = File(plugin.dataFolder, "generated_pack")
    val packZipFile: File get() = File(plugin.dataFolder, outputZipName)

    // General
    var debug: Boolean = false
        private set
    var locale: String = "th"
        private set

    // Web Editor
    var webEnabled: Boolean = true
        private set
    var webPort: Int = 8085
        private set
    var webHost: String = "0.0.0.0"
        private set
    var webSecretToken: String = ""
        private set
    var corsAllowedOrigins: List<String> = listOf("*")
        private set

    // Resource Pack Base
    var autoGenerateOnStartup: Boolean = true
        private set
    var autoZip: Boolean = true
        private set
    var outputZipName: String = "LuminaHUD-Pack.zip"
        private set
    var packDescription: String = "§bLuminaHUD §7- Clean Room Vector HUD Resource Pack"
        private set
    var packFormat: Int = 48
        private set
    var maxTileSize: Int = 256
        private set
    var antiAliasing: Boolean = true
        private set
    var fontNamespace: String = "luminahud"
        private set
    var puaStartHex: String = "E000"
        private set
    var puaStartCode: Int = 0xE000
        private set
    var negativeSpaceEnabled: Boolean = true
        private set
    var minNegativeOffset: Int = -1
        private set
    var maxNegativeOffset: Int = -1024
        private set

    // Resource Pack Delivery (4 Modes)
    var deliveryMode: String = "R2_S3"
        private set
    var deliverySendOnJoin: Boolean = true
        private set
    var deliveryDelayTicks: Long = 20L
        private set
    var deliveryRequired: Boolean = false
        private set
    var deliveryPromptMessage: String = "<gradient:#58a6ff:#7ee787>กำลังดาวน์โหลด LuminaHUD Resource Pack...</gradient>"
        private set
    var deliveryAutoBroadcast: Boolean = true
        private set
    var deliveryExternalUrl: String = ""
        private set

    // Cloudflare R2 / S3
    var r2Endpoint: String = "https://a9eccff36d623b24fcc9d0ec7a2ffc87.r2.cloudflarestorage.com"
        private set
    var r2BucketName: String = "luminaris-hud"
        private set
    var r2AccessKeyId: String = "b046c72af65dec83a1b80bcdfb026dfb"
        private set
    var r2SecretAccessKey: String = "31ec5d15a2be03e33449d42f02cd5d81c3e966be213d92976f9b199720561c94"
        private set
    var r2PublicBaseUrl: String = ""
        private set

    // Auto-Merge
    var autoMergeEnabled: Boolean = false
        private set
    var autoMergeTargetPlugin: String = "NEXO"
        private set
    var customTargetPath: String = ""
        private set

    init {
        setupDirectories()
        loadConfig()
    }

    fun setupDirectories() {
        if (!plugin.dataFolder.exists()) {
            plugin.dataFolder.mkdirs()
        }
        if (!hudsFolder.exists()) {
            hudsFolder.mkdirs()
        }
        if (!generatedPackFolder.exists()) {
            generatedPackFolder.mkdirs()
        }

        // สร้าง default config.yml หากยังไม่มี
        if (!configFile.exists()) {
            plugin.saveResource("config.yml", false)
        }

        // สร้าง default_hud.yml หากโฟลเดอร์ huds ว่าง
        val defaultHudFile = File(hudsFolder, "default_hud.yml")
        if (!defaultHudFile.exists()) {
            plugin.saveResource("huds/default_hud.yml", false)
        }
    }

    fun loadConfig() {
        setupDirectories()
        val config = YamlConfiguration.loadConfiguration(configFile)

        // General
        debug = config.getBoolean("general.debug", false)
        locale = config.getString("general.locale", "th") ?: "th"

        // Web Editor
        webEnabled = config.getBoolean("web-editor.enabled", true)
        webPort = config.getInt("web-editor.port", 8085)
        webHost = config.getString("web-editor.host", "0.0.0.0") ?: "0.0.0.0"

        var token = config.getString("web-editor.secret-token", "") ?: ""
        if (token.isBlank() || token == "lumina-hud-secure-token-change-me") {
            token = "lhud_" + UUID.randomUUID().toString().replace("-", "").take(16)
            config.set("web-editor.secret-token", token)
            try {
                config.save(configFile)
            } catch (e: Exception) {
                plugin.logger.warning("ไม่สามารถบันทึก Token อัตโนมัติลง config.yml ได้: ${e.message}")
            }
        }
        webSecretToken = token
        corsAllowedOrigins = config.getStringList("web-editor.cors-allowed-origins").ifEmpty { listOf("*") }

        // Resource Pack Base
        autoGenerateOnStartup = config.getBoolean("resource-pack.auto-generate-on-startup", true)
        autoZip = config.getBoolean("resource-pack.auto-zip", true)
        outputZipName = config.getString("resource-pack.output-zip-name", "LuminaHUD-Pack.zip") ?: "LuminaHUD-Pack.zip"
        packDescription = config.getString("resource-pack.description", "§bLuminaHUD §7- Clean Room Vector HUD Resource Pack") ?: "LuminaHUD"
        packFormat = config.getInt("resource-pack.pack-format", 48)
        maxTileSize = config.getInt("resource-pack.svg-rasterizer.max-tile-size", 256).coerceIn(16, 1024)
        antiAliasing = config.getBoolean("resource-pack.svg-rasterizer.anti-aliasing", true)
        fontNamespace = config.getString("resource-pack.font-mapping.namespace", "luminahud") ?: "luminahud"
        puaStartHex = config.getString("resource-pack.font-mapping.pua-start-hex", "E000") ?: "E000"
        puaStartCode = try {
            Integer.parseInt(puaStartHex, 16)
        } catch (_: Exception) {
            0xE000
        }

        // Negative Space
        negativeSpaceEnabled = config.getBoolean("resource-pack.negative-space.enabled", true)
        minNegativeOffset = config.getInt("resource-pack.negative-space.min-offset", -1)
        maxNegativeOffset = config.getInt("resource-pack.negative-space.max-offset", -1024)

        // Delivery
        deliveryMode = config.getString("resource-pack.delivery.mode", "R2_S3") ?: "R2_S3"
        deliverySendOnJoin = config.getBoolean("resource-pack.delivery.send-on-join", true)
        deliveryDelayTicks = config.getLong("resource-pack.delivery.delay-ticks", 20L)
        deliveryRequired = config.getBoolean("resource-pack.delivery.required", false)
        deliveryPromptMessage = config.getString("resource-pack.delivery.prompt-message", "<gradient:#58a6ff:#7ee787>กำลังดาวน์โหลด LuminaHUD Resource Pack...</gradient>") ?: ""
        deliveryAutoBroadcast = config.getBoolean("resource-pack.delivery.auto-broadcast-on-pack", true)
        deliveryExternalUrl = config.getString("resource-pack.delivery.external-url", "") ?: ""

        // Cloudflare R2 / S3
        r2Endpoint = config.getString("resource-pack.delivery.r2-s3.endpoint", "https://a9eccff36d623b24fcc9d0ec7a2ffc87.r2.cloudflarestorage.com") ?: "https://a9eccff36d623b24fcc9d0ec7a2ffc87.r2.cloudflarestorage.com"
        r2BucketName = config.getString("resource-pack.delivery.r2-s3.bucket-name", "luminaris-hud") ?: "luminaris-hud"
        r2AccessKeyId = config.getString("resource-pack.delivery.r2-s3.access-key-id", "b046c72af65dec83a1b80bcdfb026dfb") ?: "b046c72af65dec83a1b80bcdfb026dfb"
        r2SecretAccessKey = config.getString("resource-pack.delivery.r2-s3.secret-access-key", "31ec5d15a2be03e33449d42f02cd5d81c3e966be213d92976f9b199720561c94") ?: "31ec5d15a2be03e33449d42f02cd5d81c3e966be213d92976f9b199720561c94"
        r2PublicBaseUrl = config.getString("resource-pack.delivery.r2-s3.public-base-url", "") ?: ""

        // Auto Merge
        autoMergeEnabled = config.getBoolean("auto-merge.enabled", false)
        autoMergeTargetPlugin = config.getString("auto-merge.target-plugin", "NEXO") ?: "NEXO"
        customTargetPath = config.getString("auto-merge.custom-target-path", "") ?: ""
    }
}
