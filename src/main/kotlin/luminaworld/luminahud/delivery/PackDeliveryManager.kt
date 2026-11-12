package luminaworld.luminahud.delivery

import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import luminaworld.luminahud.LuminaHUDPlugin
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * ผู้จัดการการส่ง Resource Pack สู่ผู้เล่นในเกม (รองรับ R2_S3, MC_PACKS, LOCAL_HOST, EXTERNAL_OR_MERGE)
 */
class PackDeliveryManager(private val plugin: LuminaHUDPlugin) {

    private val miniMessage = MiniMessage.miniMessage()

    var currentPackUrl: String? = null
        private set
    var currentPackHash: ByteArray? = null
        private set
    var currentPackHashHex: String? = null
        private set

    /**
     * ประมวลผลอัปโหลดไฟล์ และกระจายแพ็กใหม่สู่ผู้เล่น
     * @param broadcastToAll ส่งแพ็กให้ผู้เล่นทุกคนทันทีหรือไม่ (ค่าเริ่มต้น: false ไม่ส่ง)
     */
    fun processAndDeploy(zipFile: File, broadcastToAll: Boolean = false): CompletableFuture<Boolean> {
        return CompletableFuture.supplyAsync {
            if (!zipFile.exists() || zipFile.length() == 0L) {
                plugin.logger.warning("[Pack Delivery] ไม่พบไฟล์ ${zipFile.name} หรือไฟล์มีขนาด 0 bytes")
                return@supplyAsync false
            }

            try {
                // 1. คำนวณ SHA-1 Hash
                val hashBytes = calculateSha1(zipFile)
                currentPackHash = hashBytes
                currentPackHashHex = bytesToHex(hashBytes)

                val config = plugin.configManager
                val mode = config.deliveryMode

                plugin.logger.info("[Pack Delivery] กำลังเตรียมส่ง Resource Pack ในโหมด: $mode (SHA-1: $currentPackHashHex)")

                // 2. เลือกวิธีการสร้าง/อัปโหลด URL ตามโหมด
                when (mode.uppercase()) {
                    "R2_S3" -> {
                        val uploader = R2S3Uploader(
                            endpoint = config.r2Endpoint,
                            bucketName = config.r2BucketName,
                            accessKeyId = config.r2AccessKeyId,
                            secretAccessKey = config.r2SecretAccessKey,
                            publicBaseUrl = config.r2PublicBaseUrl
                        )
                        val uploadedUrl = uploader.upload(zipFile, config.outputZipName)
                        currentPackUrl = uploadedUrl
                        plugin.logger.info("[Pack Delivery] อัปโหลดขึ้น Cloudflare R2 สำเร็จ: $uploadedUrl")
                    }

                    "MC_PACKS" -> {
                        val uploader = McPacksUploader()
                        val result = uploader.upload(zipFile)
                        currentPackUrl = result.downloadUrl
                        plugin.logger.info("[Pack Delivery] อัปโหลดขึ้น mc-packs.net สำเร็จ: ${result.downloadUrl}")
                    }

                    "LOCAL_HOST" -> {
                        val host = if (config.webHost == "0.0.0.0") "localhost" else config.webHost
                        currentPackUrl = "http://$host:${config.webPort}/pack/download"
                        plugin.logger.info("[Pack Delivery] โฮสต์ไฟล์ผ่าน Local Web Server: $currentPackUrl")
                    }

                    "EXTERNAL_OR_MERGE" -> {
                        if (config.deliveryExternalUrl.isNotBlank()) {
                            currentPackUrl = config.deliveryExternalUrl
                            plugin.logger.info("[Pack Delivery] ใช้ External URL: $currentPackUrl")
                        } else {
                            plugin.logger.info("[Pack Delivery] โหมด Merge ทำงานแล้ว (ปล่อยให้ปลั๊กอินเป้าหมายเป็นผู้ส่งแพ็ก)")
                            return@supplyAsync true
                        }
                    }

                    else -> {
                        plugin.logger.warning("[Pack Delivery] ไม่รู้จักโหมด $mode กรุณาตรวจสอบ config.yml")
                        return@supplyAsync false
                    }
                }

                // 3. ส่งอัปเดตให้ผู้เล่นที่ออนไลน์อยู่ทุกคนทันทีเมื่อมีคำสั่งระบุเจาะจง (broadcastToAll)
                if (broadcastToAll && currentPackUrl != null) {
                    sendPackToAll()
                }

                return@supplyAsync true
            } catch (e: Exception) {
                plugin.logger.severe("[Pack Delivery] เกิดข้อผิดพลาดขณะส่งออก Resource Pack: ${e.message}")
                if (plugin.configManager.debug) {
                    e.printStackTrace()
                }
                return@supplyAsync false
            }
        }
    }

    /**
     * ส่ง Resource Pack ไปยังผู้เล่นคนใดคนหนึ่ง
     */
    fun sendPackToPlayer(player: Player) {
        val url = currentPackUrl ?: return
        val hash = currentPackHash

        val prompt = if (plugin.configManager.deliveryPromptMessage.isNotBlank()) {
            miniMessage.deserialize(plugin.configManager.deliveryPromptMessage)
        } else null

        val required = plugin.configManager.deliveryRequired

        val hashBytes = hash ?: ByteArray(0)

        // รองรับ Folia Entity Scheduler
        try {
            val scheduler = player.scheduler
            scheduler.run(plugin, { _ ->
                player.setResourcePack(UUID.randomUUID(), url, hashBytes, prompt, required)
            }, null)
        } catch (_: NoSuchMethodError) {
            // Bukkit / Paper ปกติ
            player.setResourcePack(UUID.randomUUID(), url, hashBytes, prompt, required)
        }
    }

    /**
     * ส่ง Resource Pack ไปยังผู้เล่นทุกคนที่ออนไลน์อยู่
     */
    fun sendPackToAll() {
        if (currentPackUrl == null) return
        val onlinePlayers = Bukkit.getOnlinePlayers()
        if (onlinePlayers.isEmpty()) return

        plugin.logger.info("[Pack Delivery] กำลังส่ง Resource Pack อัปเดตไปยังผู้เล่น ${onlinePlayers.size} คน...")
        for (player in onlinePlayers) {
            sendPackToPlayer(player)
        }
    }

    private fun calculateSha1(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-1")
        return digest.digest(file.readBytes())
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format("%02x", b.toInt() and 0xFF))
        }
        return sb.toString()
    }
}
