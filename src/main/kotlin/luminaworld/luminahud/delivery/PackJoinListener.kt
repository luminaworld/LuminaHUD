package luminaworld.luminahud.delivery

import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import luminaworld.luminahud.LuminaHUDPlugin

/**
 * ดักจับเหตุการณ์ผู้เล่นเข้าเซิร์ฟเวอร์ เพื่อส่ง Resource Pack ให้อัตโนมัติ
 */
class PackJoinListener(private val plugin: LuminaHUDPlugin) : Listener {

    @EventHandler(priority = EventPriority.HIGH)
    fun onPlayerJoin(event: PlayerJoinEvent) {
        if (!plugin.configManager.deliverySendOnJoin) return

        val player = event.player
        val delayTicks = plugin.configManager.deliveryDelayTicks.coerceAtLeast(1L)

        // หน่วงเวลาตามกำหนดก่อนส่ง เพื่อให้ Client โหลดหน้าจอเสร็จสมบูรณ์
        try {
            // Folia Entity Scheduler
            player.scheduler.runDelayed(plugin, { _ ->
                if (player.isOnline) {
                    plugin.packDeliveryManager.sendPackToPlayer(player)
                }
            }, null, delayTicks)
        } catch (_: NoSuchMethodError) {
            // Bukkit / Paper Scheduler Fallback
            Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                if (player.isOnline) {
                    plugin.packDeliveryManager.sendPackToPlayer(player)
                }
            }, delayTicks)
        }
    }
}

