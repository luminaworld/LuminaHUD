package luminaworld.luminahud.command

import io.papermc.paper.command.brigadier.BasicCommand
import io.papermc.paper.command.brigadier.CommandSourceStack
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import luminaworld.luminahud.LuminaHUDPlugin
import luminaworld.luminahud.generator.PackBuildResult
import java.util.concurrent.CompletableFuture
import java.util.function.Supplier

/**
 * ผู้จัดการคำสั่งหลักของปลั๊กอิน LuminaHUD ตามมาตรฐาน Paper BasicCommand
 */
class LuminaHUDCommand(private val plugin: LuminaHUDPlugin) : BasicCommand {

    private val miniMessage = MiniMessage.miniMessage()

    override fun execute(stack: CommandSourceStack, args: Array<String>) {
        val sender = stack.sender
        if (!sender.hasPermission("luminahud.admin")) {
            sender.sendMessage(miniMessage.deserialize("<red>คุณไม่มีสิทธิ์ใช้งานคำสั่งนี้ (ต้องการสิทธิ์: luminahud.admin)</red>"))
            return
        }

        if (args.isEmpty() || args[0].equals("help", ignoreCase = true)) {
            sendHelp(sender, "luminahud")
            return
        }

        when (args[0].lowercase()) {
            "editor", "studio" -> handleEditor(sender)
            "reload" -> handleReload(sender)
            "pack" -> handlePack(sender, args)
            "apply" -> handleApply(sender, args)
            "status" -> handleStatus(sender)
            else -> {
                sender.sendMessage(miniMessage.deserialize("<red>ไม่พบคำสั่งย่อย <yellow>${args[0]}</yellow> กรุณาใช้ <yellow>/luminahud help</yellow> เพื่อดูคำสั่งทั้งหมด</red>"))
            }
        }
    }

    override fun suggest(stack: CommandSourceStack, args: Array<String>): Collection<String> {
        val sender = stack.sender
        if (!sender.hasPermission("luminahud.admin")) return emptyList()

        if (args.size <= 1) {
            val prefix = if (args.isEmpty()) "" else args[0]
            val subcommands = listOf("editor", "reload", "pack", "apply", "status", "help")
            return subcommands.filter { it.startsWith(prefix, ignoreCase = true) }
        }

        if (args.size == 2) {
            if (args[0].equals("pack", ignoreCase = true)) {
                return listOf("applyto").filter { it.startsWith(args[1], ignoreCase = true) }
            }
            if (args[0].equals("apply", ignoreCase = true)) {
                val options = mutableListOf("all")
                options.addAll(Bukkit.getOnlinePlayers().map { it.name })
                return options.filter { it.startsWith(args[1], ignoreCase = true) }
            }
        }

        if (args.size == 3 && args[0].equals("pack", ignoreCase = true) && args[1].equals("applyto", ignoreCase = true)) {
            val options = mutableListOf("all")
            options.addAll(Bukkit.getOnlinePlayers().map { it.name })
            return options.filter { it.startsWith(args[2], ignoreCase = true) }
        }

        return emptyList()
    }

    override fun permission(): String = "luminahud.admin"

    override fun canUse(sender: CommandSender): Boolean {
        return sender.hasPermission("luminahud.admin")
    }

    private fun handleEditor(sender: CommandSender) {
        if (!plugin.configManager.webEnabled || !plugin.webServer.isRunning) {
            sender.sendMessage(miniMessage.deserialize("<red>[LuminaHUD] ระบบ Web Studio ปิดใช้งานอยู่ หรือ Web Server ยังไม่ได้เปิดทำงาน (ตรวจสอบ config.yml: web-editor.enabled)</red>"))
            return
        }

        val token = plugin.authTokenManager.createToken()
        val host = if (plugin.configManager.webHost == "0.0.0.0") "localhost" else plugin.configManager.webHost
        val port = plugin.configManager.webPort
        val url = "http://$host:$port/?token=$token"

        sender.sendMessage(miniMessage.deserialize(
            """
            <gradient:#C351F5:#58A6FF><b>============= [ LuminaHUD Web Studio ] =============</b></gradient>
            <gray>ลิงก์สำหรับเข้าใช้งาน Web Editor แบบ One-Time Auth (มีอายุ 15 นาที):</gray>
            <click:open_url:'$url'><hover:show_text:'<gradient:#C351F5:#58A6FF>คลิกเพื่อเปิดหน้าเว็บ LuminaHUD Web Studio</gradient>'><underlined><aqua>$url</aqua></underlined></hover></click>
            <gray>  • Token ชั่วคราว:</gray> <yellow>$token</yellow>
            <gray>  • ข้อแนะนำ:</gray> <white>หากใช้งานผ่านเครือข่ายภายนอก ให้เปลี่ยน localhost เป็น IP หรือโดเมนของเซิร์ฟเวอร์</white>
            <gradient:#C351F5:#58A6FF>===================================================</gradient>
            """.trimIndent()
        ))
    }

    private fun handleReload(sender: CommandSender) {
        sender.sendMessage(miniMessage.deserialize("<gradient:#58a6ff:#7ee787>[LuminaHUD]</gradient> <gray>กำลังรีโหลดการตั้งค่าระบบ...</gray>"))

        try {
            // โหลดคอนฟิกใหม่
            plugin.configManager.loadConfig()

            // รีสตาร์ต Web Server หากจำเป็น
            if (plugin.configManager.webEnabled) {
                plugin.webServer.stop()
                plugin.webServer.start()
            } else if (plugin.webServer.isRunning) {
                plugin.webServer.stop()
            }

            sender.sendMessage(miniMessage.deserialize("<gradient:#58a6ff:#7ee787>[LuminaHUD]</gradient> <green>รีโหลดการตั้งค่าและไฟล์ HUD ทั้งหมดเรียบร้อยแล้ว!</green>"))
        } catch (e: Exception) {
            sender.sendMessage(miniMessage.deserialize("<red>[LuminaHUD] เกิดข้อผิดพลาดขณะรีโหลด: ${e.message}</red>"))
        }
    }

    private fun handlePack(sender: CommandSender, args: Array<String>) {
        val applyTarget = if (args.size >= 3 && args[1].equals("applyto", ignoreCase = true)) {
            args[2]
        } else {
            null
        }

        sender.sendMessage(miniMessage.deserialize("<gradient:#58a6ff:#7ee787>[LuminaHUD]</gradient> <yellow>กำลังเริ่มแปลง SVG, ประกอบ Resource Pack และส่งออก (Asynchronous)...</yellow>"))

        CompletableFuture.supplyAsync(Supplier<PackBuildResult> {
            val result = plugin.packBuilder.buildPack()
            if (result.success && result.zipFile != null) {
                // ส่งต่อไปยัง PackDeliveryManager โดยไม่ broadcast อัตโนมัติ
                plugin.packDeliveryManager.processAndDeploy(result.zipFile, broadcastToAll = false).join()
            }
            result
        }).thenAccept { result ->
            if (result.success) {
                val packUrl = plugin.packDeliveryManager.currentPackUrl ?: "ไม่มี (Merge Mode หรือไม่ได้เปิดโฮสต์)"
                val packHash = plugin.packDeliveryManager.currentPackHashHex ?: "ไม่มี"

                if (applyTarget == null) {
                    // /luminahud pack เฉยๆ: ไม่อัปเดตส่งให้ใคร
                    sender.sendMessage(miniMessage.deserialize(
                        """
                        <gradient:#58a6ff:#7ee787>[LuminaHUD] สร้างและอัปโหลด Resource Pack สำเร็จ!</gradient>
                        <gray>  • เวลาที่ใช้:</gray> <white>${result.durationMs} ms</white>
                        <gray>  • เลเยอร์ที่ประมวลผล:</gray> <white>${result.totalLayersProcessed}</white>
                        <gray>  • Tiles ที่สร้าง:</gray> <white>${result.totalTilesGenerated}</white>
                        <gray>  • ขนาดไฟล์ ZIP:</gray> <white>${String.format("%.2f", result.zipSizeBytes / 1024.0)} KB</white>
                        <gray>  • โหมด Delivery:</gray> <aqua>${plugin.configManager.deliveryMode}</aqua>
                        <gray>  • SHA-1 Hash:</gray> <white>$packHash</white>
                        <gray>  • Pack URL:</gray> <underlined><aqua>$packUrl</aqua></underlined>
                        <gray>  • สถานะ:</gray> <yellow>ยังไม่ได้ส่งให้ผู้เล่น (ใช้ <white>/luminahud pack applyto [all/player]</white> หรือ <white>/luminahud apply</white>)</yellow>
                        """.trimIndent()
                    ))
                } else if (applyTarget.equals("all", ignoreCase = true)) {
                    // /luminahud pack applyto all
                    plugin.packDeliveryManager.sendPackToAll()
                    sender.sendMessage(miniMessage.deserialize(
                        """
                        <gradient:#58a6ff:#7ee787>[LuminaHUD] สร้าง อัปโหลด และส่ง Resource Pack ให้ผู้เล่นทุกคนเรียบร้อยแล้ว!</gradient>
                        <gray>  • เวลาที่ใช้:</gray> <white>${result.durationMs} ms</white>
                        <gray>  • ผู้รับ:</gray> <green>ผู้เล่นออนไลน์ทุกคน (All Players)</green>
                        <gray>  • Pack URL:</gray> <underlined><aqua>$packUrl</aqua></underlined>
                        """.trimIndent()
                    ))
                } else {
                    // /luminahud pack applyto <player>
                    val targetPlayer = Bukkit.getPlayerExact(applyTarget)
                    if (targetPlayer != null && targetPlayer.isOnline) {
                        plugin.packDeliveryManager.sendPackToPlayer(targetPlayer)
                        sender.sendMessage(miniMessage.deserialize(
                            """
                            <gradient:#58a6ff:#7ee787>[LuminaHUD] สร้าง อัปโหลด และส่ง Resource Pack ให้กับ <white>${targetPlayer.name}</white> เรียบร้อยแล้ว!</gradient>
                            <gray>  • เวลาที่ใช้:</gray> <white>${result.durationMs} ms</white>
                            <gray>  • ผู้รับ:</gray> <aqua>${targetPlayer.name}</aqua>
                            <gray>  • Pack URL:</gray> <underlined><aqua>$packUrl</aqua></underlined>
                            """.trimIndent()
                        ))
                    } else {
                        sender.sendMessage(miniMessage.deserialize(
                            """
                            <gradient:#58a6ff:#7ee787>[LuminaHUD] สร้างและอัปโหลด Resource Pack สำเร็จ!</gradient>
                            <red>  • ไม่พบผู้เล่น <yellow>$applyTarget</yellow> ที่ออนไลน์อยู่ (จึงไม่ได้ส่งแพ็กให้)</red>
                            <gray>  • Pack URL:</gray> <underlined><aqua>$packUrl</aqua></underlined>
                            """.trimIndent()
                        ))
                    }
                }
            } else {
                sender.sendMessage(miniMessage.deserialize("<red>[LuminaHUD] การสร้าง Resource Pack ล้มเหลว: ${result.message}</red>"))
            }
        }.exceptionally { ex ->
            sender.sendMessage(miniMessage.deserialize("<red>[LuminaHUD] เกิดข้อผิดพลาดร้ายแรง: ${ex.message}</red>"))
            null
        }
    }

    private fun handleApply(sender: CommandSender, args: Array<String>) {
        val deliveryManager = plugin.packDeliveryManager
        if (deliveryManager.currentPackUrl == null) {
            sender.sendMessage(miniMessage.deserialize("<red>[LuminaHUD] ยังไม่มี Resource Pack ที่พร้อมใช้งาน กรุณารัน <yellow>/luminahud pack</yellow> ก่อน</red>"))
            return
        }

        if (args.size == 1) {
            // ส่งให้ตัวเอง
            if (sender is Player) {
                deliveryManager.sendPackToPlayer(sender)
                sender.sendMessage(miniMessage.deserialize("<gradient:#58a6ff:#7ee787>[LuminaHUD]</gradient> <green>ส่งคำขอดาวน์โหลด Resource Pack ให้คุณเรียบร้อยแล้ว!</green>"))
            } else {
                sender.sendMessage(miniMessage.deserialize("<red>กรุณาระบุชื่อผู้เล่น: /luminahud apply <player|all></red>"))
            }
            return
        }

        val target = args[1]
        if (target.equals("all", ignoreCase = true)) {
            deliveryManager.sendPackToAll()
            sender.sendMessage(miniMessage.deserialize("<gradient:#58a6ff:#7ee787>[LuminaHUD]</gradient> <green>ส่งคำขอดาวน์โหลด Resource Pack ให้ผู้เล่นทุกคนที่ออนไลน์อยู่เรียบร้อยแล้ว!</green>"))
            return
        }

        val targetPlayer = Bukkit.getPlayerExact(target)
        if (targetPlayer == null || !targetPlayer.isOnline) {
            sender.sendMessage(miniMessage.deserialize("<red>ไม่พบผู้เล่น <yellow>$target</yellow> ที่ออนไลน์อยู่</red>"))
            return
        }

        deliveryManager.sendPackToPlayer(targetPlayer)
        sender.sendMessage(miniMessage.deserialize("<gradient:#58a6ff:#7ee787>[LuminaHUD]</gradient> <green>ส่ง Resource Pack ให้กับ <white>${targetPlayer.name}</white> เรียบร้อยแล้ว!</green>"))
    }

    private fun handleStatus(sender: CommandSender) {
        val config = plugin.configManager
        val webRunning = plugin.webServer.isRunning
        val webUrl = "http://${config.webHost}:${config.webPort}/?token=${config.webSecretToken}"
        val packUrl = plugin.packDeliveryManager.currentPackUrl ?: "ยังไม่ได้สร้าง (รัน /luminahud pack)"

        sender.sendMessage(miniMessage.deserialize(
            """
            <gradient:#58a6ff:#7ee787>============= [ LuminaHUD Status ] =============</gradient>
            <gray>  • เวอร์ชันปลั๊กอิน:</gray> <white>${plugin.pluginMeta.version}</white>
            <gray>  • สถาปัตยกรรม:</gray> <white>Clean Room (Paper / Purpur / Folia)</white>
            <gray>  • Web Editor:</gray> ${if (webRunning) "<green>ทำงานอยู่ (Online)</green>" else "<red>ปิดอยู่ (Offline)</red>"}
            <gray>  • Web URL:</gray> <click:open_url:'$webUrl'><underlined><aqua>$webUrl</aqua></underlined></click>
            <gray>  • Delivery Mode:</gray> <yellow>${config.deliveryMode}</yellow>
            <gray>  • Pack URL:</gray> <white>$packUrl</white>
            <gray>  • Auto-Send on Join:</gray> <white>${if (config.deliverySendOnJoin) "<green>เปิด</green>" else "<gray>ปิด</gray>"}</white>
            <gray>  • รหัส PUA ที่ลงทะเบียน:</gray> <yellow>${plugin.fontRegistry.totalRegistered}</yellow> <gray>ชิ้น</gray>
            <gradient:#58a6ff:#7ee787>================================================</gradient>
            """.trimIndent()
        ))
    }

    private fun sendHelp(sender: CommandSender, label: String) {
        sender.sendMessage(miniMessage.deserialize(
            """
            <gradient:#58a6ff:#7ee787>============= [ LuminaHUD Commands ] =============</gradient>
            <yellow>/$label editor</yellow> <gray>- สร้างลิงก์เข้าใช้งาน LuminaHUD Web Studio (One-Time Auth)</gray>
            <yellow>/$label reload</yellow> <gray>- รีโหลดการตั้งค่าและไฟล์ HUD ทั้งหมด</gray>
            <yellow>/$label pack [applyto all/player]</yellow> <gray>- แปลง SVG, ประกอบ Resource Pack และส่งออก (ถ้าไม่ระบุ applyto จะไม่ส่งให้ผู้เล่น)</gray>
            <yellow>/$label apply [player|all]</yellow> <gray>- ส่ง Resource Pack ให้ผู้เล่นในเกมทันที</gray>
            <yellow>/$label status</yellow> <gray>- ตรวจสอบสถานะระบบ, Web Editor และ Pack Delivery</gray>
            <yellow>/$label help</yellow> <gray>- แสดงรายการคำสั่งช่วยเหลือนี้</gray>
            <gradient:#58a6ff:#7ee787>==================================================</gradient>
            """.trimIndent()
        ))
    }
}
