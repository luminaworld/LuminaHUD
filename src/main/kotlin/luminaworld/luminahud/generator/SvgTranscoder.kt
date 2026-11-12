package luminaworld.luminahud.generator

import org.apache.batik.css.parser.Parser
import org.apache.batik.transcoder.TranscoderInput
import org.apache.batik.transcoder.TranscoderOutput
import org.apache.batik.transcoder.image.PNGTranscoder
import org.apache.batik.util.XMLResourceDescriptor
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.StringReader
import javax.imageio.ImageIO

/**
 * จัดการแปลงไฟล์หรือโค้ดเวกเตอร์ SVG เป็นภาพ Raster BufferedImage (PNG)
 * โดยใช้ Apache Batik Transcoder
 */
class SvgTranscoder(private val antiAliasing: Boolean = true) {

    init {
        // ลงทะเบียน CSS Parser Class Name ที่ถูก Relocate เพื่อป้องกันปัญหา ClassNotFound ใน Shaded Jar
        try {
            XMLResourceDescriptor.setCSSParserClassName(Parser::class.java.name)
        } catch (_: Throwable) {
        }
    }

    /**
     * แปลง SVG String เป็น BufferedImage
     */
    fun transcodeSvg(svgContent: String, targetWidth: Float? = null, targetHeight: Float? = null): BufferedImage {
        StringReader(svgContent).use { reader ->
            val input = TranscoderInput(reader)
            return executeTranscode(input, targetWidth, targetHeight)
        }
    }

    /**
     * แปลงไฟล์ SVG เป็น BufferedImage
     */
    fun transcodeSvg(svgFile: File, targetWidth: Float? = null, targetHeight: Float? = null): BufferedImage {
        FileInputStream(svgFile).use { stream ->
            return transcodeSvg(stream, targetWidth, targetHeight)
        }
    }

    /**
     * แปลง InputStream SVG เป็น BufferedImage
     */
    fun transcodeSvg(inputStream: InputStream, targetWidth: Float? = null, targetHeight: Float? = null): BufferedImage {
        val input = TranscoderInput(inputStream)
        return executeTranscode(input, targetWidth, targetHeight)
    }

    private fun executeTranscode(input: TranscoderInput, targetWidth: Float?, targetHeight: Float?): BufferedImage {
        // กำหนด ContextClassLoader ชั่วคราวเป็น ClassLoader ของปลั๊กอิน
        // เพื่อให้ Threads เบื้องหลัง (เช่น ForkJoinPool หรือ Coroutines) หาคลาสของ Batik ครบถ้วน
        val originalClassLoader = Thread.currentThread().contextClassLoader
        try {
            Thread.currentThread().contextClassLoader = SvgTranscoder::class.java.classLoader
            XMLResourceDescriptor.setCSSParserClassName(Parser::class.java.name)

            val transcoder = PNGTranscoder()

            // ตั้งค่าขนาดหากมีการระบุ
            if (targetWidth != null && targetWidth > 0f) {
                transcoder.addTranscodingHint(PNGTranscoder.KEY_WIDTH, targetWidth)
            }
            if (targetHeight != null && targetHeight > 0f) {
                transcoder.addTranscodingHint(PNGTranscoder.KEY_HEIGHT, targetHeight)
            }

            // ตั้งค่าความคมชัดและการวาดเส้น
            if (antiAliasing) {
                transcoder.addTranscodingHint(PNGTranscoder.KEY_DEFAULT_FONT_FAMILY, "SansSerif")
            }

            ByteArrayOutputStream().use { ostream ->
                val output = TranscoderOutput(ostream)
                transcoder.transcode(input, output)
                ostream.flush()

                val imageBytes = ostream.toByteArray()
                ByteArrayInputStream(imageBytes).use { bais ->
                    val image = ImageIO.read(bais) ?: throw IllegalStateException("ไม่สามารถแปลงไบต์เป็น BufferedImage ได้")
                    return image
                }
            }
        } finally {
            Thread.currentThread().contextClassLoader = originalClassLoader
            try {
                for (thread in Thread.getAllStackTraces().keys) {
                    if (thread.name.contains("CleanerThread", ignoreCase = true) ||
                        thread.javaClass.name.contains("CleanerThread", ignoreCase = true)) {
                        thread.contextClassLoader = ClassLoader.getSystemClassLoader()
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }
}
