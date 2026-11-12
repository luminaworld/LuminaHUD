package luminaworld.luminahud.font

import com.google.gson.JsonObject

/**
 * จัดการและสร้าง Negative Space Font (อักขระเลื่อนพิกัดติดลบและบวก -1 ถึง -1024 px)
 * เพื่อให้สามารถจัดตำแหน่งเลเยอร์ HUD ซ้อนทับกันได้อย่างสมบูรณ์แบบ
 */
class NegativeSpaceProvider(
    private val minOffset: Int = -1,
    private val maxOffset: Int = -1024
) {

    // ตารางค่ากำลังสองสำหรับประกอบระยะพิกเซลได้อย่างรวดเร็วและประหยัดตัวอักษร
    val powers = intArrayOf(1024, 512, 256, 128, 64, 32, 16, 8, 4, 2, 1)

    // แมปเก็บรหัส Unicode กับค่าระยะพิกเซล (Advancement)
    val charToAdvance = LinkedHashMap<Char, Int>()
    val advanceToChar = LinkedHashMap<Int, Char>()

    init {
        generateMappings()
    }

    private fun generateMappings() {
        // กำหนด Unicode ในช่วง Private Use Area \uF800 เป็นต้นไป
        var baseNegativeChar = 0xF810
        var basePositiveChar = 0xF830

        // 1. ลงทะเบียนค่ากำลังสอง (Binary Steps) ทั้งบวกและลบ
        for (pow in powers) {
            // Negative
            val negChar = (++baseNegativeChar).toChar()
            charToAdvance[negChar] = -pow
            advanceToChar[-pow] = negChar

            // Positive
            val posChar = (++basePositiveChar).toChar()
            charToAdvance[posChar] = pow
            advanceToChar[pow] = posChar
        }

        // 2. ลงทะเบียนค่าเฉพาะ -1 ถึง -64 เป็นรายตัวเพื่อความสะดวกรวดเร็ว
        var directChar = 0xF850
        for (i in -1 downTo -64) {
            if (!advanceToChar.containsKey(i)) {
                val c = (++directChar).toChar()
                charToAdvance[c] = i
                advanceToChar[i] = c
            }
        }
    }

    /**
     * แปลงระยะพิกเซล (เช่น -15 หรือ +20) เป็นลำดับอักขระ Negative Space
     */
    fun buildSpace(offset: Int): String {
        if (offset == 0) return ""

        // หากมีอักขระตรงตัวอยู่แล้ว ให้ส่งคืนทันที
        advanceToChar[offset]?.let { return it.toString() }

        val sb = StringBuilder()
        var remaining = offset

        if (remaining < 0) {
            var positiveRemaining = -remaining
            for (p in powers) {
                while (positiveRemaining >= p) {
                    val c = advanceToChar[-p]
                    if (c != null) {
                        sb.append(c)
                        positiveRemaining -= p
                    } else {
                        break
                    }
                }
            }
        } else {
            for (p in powers) {
                while (remaining >= p) {
                    val c = advanceToChar[p]
                    if (c != null) {
                        sb.append(c)
                        remaining -= p
                    } else {
                        break
                    }
                }
            }
        }

        return sb.toString()
    }

    /**
     * สร้าง JsonObject โครงสร้าง "type": "space" สำหรับ Minecraft Font Provider
     */
    fun toJsonProvider(): JsonObject {
        val provider = JsonObject()
        provider.addProperty("type", "space")

        val advancesObj = JsonObject()
        for ((char, adv) in charToAdvance) {
            advancesObj.addProperty(char.toString(), adv)
        }
        provider.add("advances", advancesObj)
        return provider
    }
}

