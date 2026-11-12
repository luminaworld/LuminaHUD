package luminaworld.luminahud.font

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * ข้อมูลของ Glyph หรือชิ้นส่วนภาพที่แปลงเป็น Font
 */
data class GlyphInfo(
    val id: String,
    val unicode: Char,
    val texturePath: String,
    val ascent: Int,
    val height: Int,
    val tileIndex: Int = 0,
    val totalTiles: Int = 1
)

/**
 * Registry สำหรับจัดการแมปปิ้งตัวอักษร Unicode Private Use Area (PUA)
 */
class FontRegistry(private var startCode: Int = 0xE000) {

    private val counter = AtomicInteger(startCode)
    private val elementToGlyphs = ConcurrentHashMap<String, MutableList<GlyphInfo>>()
    private val idToGlyph = ConcurrentHashMap<String, GlyphInfo>()

    fun reset(newStartCode: Int = startCode) {
        startCode = newStartCode
        counter.set(newStartCode)
        elementToGlyphs.clear()
        idToGlyph.clear()
    }

    /**
     * กำหนดรหัส Unicode ใหม่ให้กับ Texture/Layer ชิ้นนี้
     */
    fun allocateGlyph(
        elementId: String,
        texturePath: String,
        ascent: Int,
        height: Int,
        tileIndex: Int = 0,
        totalTiles: Int = 1
    ): GlyphInfo {
        val nextCode = counter.getAndIncrement()
        val unicode = nextCode.toChar()
        val subId = if (totalTiles > 1) "${elementId}_tile_$tileIndex" else elementId

        val glyph = GlyphInfo(
            id = subId,
            unicode = unicode,
            texturePath = texturePath,
            ascent = ascent,
            height = height,
            tileIndex = tileIndex,
            totalTiles = totalTiles
        )

        idToGlyph[subId] = glyph
        elementToGlyphs.computeIfAbsent(elementId) { mutableListOf() }.add(glyph)
        return glyph
    }

    fun getGlyph(id: String): GlyphInfo? = idToGlyph[id]

    fun getGlyphsForElement(elementId: String): List<GlyphInfo> {
        return elementToGlyphs[elementId] ?: emptyList()
    }

    fun getAllMappings(): Map<String, List<GlyphInfo>> = HashMap(elementToGlyphs)

    fun getAllGlyphs(): List<GlyphInfo> = idToGlyph.values.toList()

    val totalRegistered: Int get() = idToGlyph.size
}

