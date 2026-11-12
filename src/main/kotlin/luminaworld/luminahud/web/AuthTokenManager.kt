package luminaworld.luminahud.web

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * ผู้จัดการ Token ความปลอดภัยชั่วคราว (One-Time / Temporary Auth Tokens)
 * สำหรับแอดมินที่สร้าง URL เข้าใช้งาน Web Studio จากคำสั่งในเกม (/luminahud editor)
 */
class AuthTokenManager {

    private val activeTokens = ConcurrentHashMap<String, Long>()
    private val random = SecureRandom()
    private val charPool = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

    /**
     * สร้าง Token ชั่วคราวชุดใหม่
     * @param validityDurationMillis ระยะเวลาหมดอายุ (ค่าเริ่มต้น: 15 นาที)
     * @return รหัส Token ความปลอดภัย
     */
    fun createToken(validityDurationMillis: Long = 15 * 60 * 1000L): String {
        cleanupExpired()

        val sb = StringBuilder(24)
        for (i in 0 until 24) {
            sb.append(charPool[random.nextInt(charPool.length)])
        }
        val token = sb.toString()
        val expiresAt = System.currentTimeMillis() + validityDurationMillis
        activeTokens[token] = expiresAt
        return token
    }

    /**
     * ตรวจสอบว่า Token ยังคงใช้งานได้และยังไม่หมดอายุ
     */
    fun isValid(token: String): Boolean {
        if (token.isBlank()) return false
        val expiresAt = activeTokens[token] ?: return false
        if (System.currentTimeMillis() > expiresAt) {
            activeTokens.remove(token)
            return false
        }
        return true
    }

    /**
     * ลบล้าง Token เมื่อต้องการเพิกถอนสิทธิ์ทันที
     */
    fun revoke(token: String) {
        activeTokens.remove(token)
    }

    /**
     * ล้าง Token ที่หมดอายุแล้วทั้งหมดออกจากหน่วยความจำ
     */
    fun cleanupExpired() {
        val now = System.currentTimeMillis()
        activeTokens.entries.removeIf { it.value < now }
    }
}

