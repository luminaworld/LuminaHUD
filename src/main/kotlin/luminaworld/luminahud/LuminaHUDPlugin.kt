package luminaworld.luminahud

import luminaworld.luminahud.command.LuminaHUDCommand
import luminaworld.luminahud.config.ConfigManager
import luminaworld.luminahud.delivery.PackDeliveryManager
import luminaworld.luminahud.delivery.PackJoinListener
import luminaworld.luminahud.font.FontRegistry
import luminaworld.luminahud.font.NegativeSpaceProvider
import luminaworld.luminahud.generator.PackBuilder
import luminaworld.luminahud.generator.PackMerger
import luminaworld.luminahud.web.AuthTokenManager
import luminaworld.luminahud.web.WebServer
import org.bukkit.Bukkit
import org.bukkit.event.HandlerList
import org.bukkit.plugin.java.JavaPlugin
import java.util.concurrent.CompletableFuture

/**
 * ปลั๊กอิน LuminaHUD - Clean Room SVG-to-Font Engine สำหรับ Paper / Purpur / Folia
 */
class LuminaHUDPlugin : JavaPlugin() {

    lateinit var configManager: ConfigManager
        private set
    lateinit var fontRegistry: FontRegistry
        private set
    lateinit var negativeSpaceProvider: NegativeSpaceProvider
        private set
    lateinit var packMerger: PackMerger
        private set
    lateinit var packBuilder: PackBuilder
        private set
    lateinit var packDeliveryManager: PackDeliveryManager
        private set
    lateinit var authTokenManager: AuthTokenManager
        private set
    lateinit var webServer: WebServer
        private set

    override fun onEnable() {
        // 0. ซ่อมแซมและปลดล็อค ClassLoader ของ Batik CleanerThread ทันทีเพื่อแก้บั๊ก NPE ที่ค้างมาจาก Reload รอบก่อน
        fixBatikCleanerThreads()

        // 1. โหลดระบบคอนฟิก
        configManager = ConfigManager(this)

        // 2. โหลด Font Registry & Negative Space Provider
        fontRegistry = FontRegistry(configManager.puaStartCode)
        negativeSpaceProvider = NegativeSpaceProvider(
            minOffset = configManager.minNegativeOffset,
            maxOffset = configManager.maxNegativeOffset
        )

        // 3. โหลด Pack Merger, Pack Builder & Pack Delivery Manager
        packMerger = PackMerger(this)
        packBuilder = PackBuilder(this, fontRegistry, negativeSpaceProvider, packMerger)
        packDeliveryManager = PackDeliveryManager(this)

        // 4. ลงทะเบียน Event Listener สำหรับส่ง Resource Pack เมื่อผู้เล่นเข้าเกม
        server.pluginManager.registerEvents(PackJoinListener(this), this)

        // 5. สั่งสร้าง Resource Pack และ Deploy ในตอนเปิดเซิร์ฟเวอร์ (โดยไม่ส่งให้ผู้เล่นอัตโนมัติ)
        if (configManager.autoGenerateOnStartup) {
            CompletableFuture.supplyAsync {
                val result = packBuilder.buildPack()
                if (result.success && result.zipFile != null) {
                    packDeliveryManager.processAndDeploy(result.zipFile, broadcastToAll = false).join()
                }
                result
            }.thenAccept { result ->
                if (result.success) {
                    logger.info("[Auto-Pack] สร้างและ Deploy Resource Pack เริ่มต้นเรียบร้อย (${result.durationMs}ms, ${result.totalTilesGenerated} tiles) [ไม่อัปเดตส่งให้ผู้เล่นอัตโนมัติ]")
                } else {
                    logger.warning("[Auto-Pack] ไม่สามารถสร้าง Resource Pack เริ่มต้นได้: ${result.message}")
                }
            }
        } else if (configManager.packZipFile.exists()) {
            // หากมีไฟล์ ZIP เดิมอยู่แล้ว ให้คำนวณและ Deploy ทันทีโดยไม่ broadcast
            packDeliveryManager.processAndDeploy(configManager.packZipFile, broadcastToAll = false)
        }

        // 6. เริ่มต้น Web Server ฝังตัว
        authTokenManager = AuthTokenManager()
        webServer = WebServer(this)
        if (configManager.webEnabled) {
            webServer.start()
        }

        // 7. ล้างคำสั่งเก่าก่อน (กรณีรีโหลดด้วย PlugManX) แล้วลงทะเบียนใหม่
        unregisterCommands()
        val hudCmd = LuminaHUDCommand(this)
        registerCommand(
            "luminahud",
            "คำสั่งหลักสำหรับจัดการปลั๊กอิน LuminaHUD",
            listOf("lhud", "hud"),
            hudCmd
        )

        logger.info("==================================================")
        logger.info(" LuminaHUD v${pluginMeta.version} เริ่มต้นทำงานเรียบร้อยแล้ว!")
        logger.info(" แพลตฟอร์ม: Paper/Folia Compatible (Clean Room)")
        logger.info(" Pack Delivery Mode: ${configManager.deliveryMode}")
        logger.info(" Web Editor: ${if (configManager.webEnabled) "พอร์ต ${configManager.webPort}" else "ปิดใช้งาน"}")
        logger.info("==================================================")
    }

    override fun onDisable() {
        // 1. ถอน Event Listeners ทั้งหมดเพื่อป้องกัน Memory Leak
        HandlerList.unregisterAll(this)

        // 2. ล้างคำสั่งออกจาก Bukkit SimpleCommandMap ป้องกันการค้างเมื่อรีโหลดด้วย PlugManX
        unregisterCommands()

        // 3. ปิด Web Server และปลดล็อค Socket Port ทันที
        if (::webServer.isInitialized && webServer.isRunning) {
            webServer.stop()
        }

        // 4. ทำความสะอาดเธรด Apache Batik (CleanerThread) เพื่อไม่ให้ PlugManX ตรวจพบ Leak
        cleanupBatikThreads()

        logger.info("LuminaHUD ปิดการทำงานเรียบร้อยแล้ว (Clean Teardown Complete)")
    }

    /**
     * ซ่อมแซม ReferenceQueue ของ Batik CleanerThread หากถูกเซ็ตเป็น null มาก่อนหน้า
     * เพื่อหยุดลูป NullPointerException ที่ทำให้เซิร์ฟเวอร์ค้างทันที
     */
    private fun fixBatikCleanerThreads() {
        try {
            for (thread in Thread.getAllStackTraces().keys) {
                val name = thread.name
                val className = thread.javaClass.name
                if (name.contains("CleanerThread", ignoreCase = true) ||
                    className.contains("CleanerThread", ignoreCase = true)) {

                    // ปลด ContextClassLoader ไม่ให้ผูกกับ PluginClassLoader
                    thread.contextClassLoader = ClassLoader.getSystemClassLoader()

                    // ซ่อมแซม ReferenceQueue หากถูกเซ็ตเป็น null จากเวอร์ชันก่อนหน้า
                    val clazz = thread.javaClass
                    val queueField = runCatching {
                        clazz.getDeclaredField("queue").apply { isAccessible = true }
                    }.getOrNull()

                    if (queueField != null && queueField.get(null) == null) {
                        queueField.set(null, java.lang.ref.ReferenceQueue<Any>())
                        logger.info("[LuminaHUD] ตรวจพบและซ่อมแซม ReferenceQueue ของ Batik สำเร็จ (ยุติลูป NPE ทันที)")
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * ปลด ContextClassLoader ของเธรด Apache Batik (CleanerThread) เมื่อปิดปลั๊กอิน
     * เพื่อป้องกัน PlugManX แจ้งเตือนว่ามี ClassLoader Leak (โดยไม่แตะต้อง queue)
     */
    private fun cleanupBatikThreads() {
        try {
            for (thread in Thread.getAllStackTraces().keys) {
                val name = thread.name
                val className = thread.javaClass.name
                if (name.contains("CleanerThread", ignoreCase = true) ||
                    className.contains("CleanerThread", ignoreCase = true)) {
                    thread.contextClassLoader = ClassLoader.getSystemClassLoader()
                }
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * ถอนการลงทะเบียนคำสั่งออกจาก CommandMap เพื่อความเข้ากันได้ 100% กับ PlugManX
     */
    private fun unregisterCommands() {
        try {
            val srv = Bukkit.getServer()
            val getCommandMapMethod = runCatching {
                srv.javaClass.getMethod("getCommandMap")
            }.getOrNull()

            val commandMap = if (getCommandMapMethod != null) {
                getCommandMapMethod.invoke(srv) as? org.bukkit.command.CommandMap
            } else {
                val field = srv.javaClass.getDeclaredField("commandMap").apply { isAccessible = true }
                field.get(srv) as? org.bukkit.command.CommandMap
            } ?: return

            val knownCommandsField = runCatching {
                commandMap.javaClass.getDeclaredField("knownCommands").apply { isAccessible = true }
            }.getOrNull() ?: return

            @Suppress("UNCHECKED_CAST")
            val knownCommands = knownCommandsField.get(commandMap) as? MutableMap<String, org.bukkit.command.Command> ?: return

            val pluginPrefix = name.lowercase()
            val targetNames = setOf("luminahud", "lhud", "hud")

            val toRemove = knownCommands.filter { (key, cmd) ->
                val lowerKey = key.lowercase()
                targetNames.contains(lowerKey) ||
                targetNames.any { lowerKey == "$pluginPrefix:$it" } ||
                (cmd is org.bukkit.command.PluginIdentifiableCommand && cmd.plugin == this)
            }

            for ((key, cmd) in toRemove) {
                runCatching { cmd.unregister(commandMap) }
                knownCommands.remove(key)
            }
        } catch (_: Throwable) {
            // ไม่ให้การ Unregister คำสั่งทำให้ปลั๊กอินล้มเหลว
        }
    }
}
