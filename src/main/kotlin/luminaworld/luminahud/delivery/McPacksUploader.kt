package luminaworld.luminahud.delivery

import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.UUID

/**
 * ผลลัพธ์จากการอัปโหลดไปยัง mc-packs.net
 */
data class McPacksUploadResult(
    val downloadUrl: String,
    val sha1Hash: String
)

/**
 * ตัวอัปโหลดไปยัง mc-packs.net แบบ Zero-Port
 */
class McPacksUploader {

    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    fun upload(file: File): McPacksUploadResult {
        val boundary = "----LuminaHUDBoundary" + UUID.randomUUID().toString().replace("-", "")
        val lineEnd = "\r\n"

        val fileBytes = file.readBytes()

        val output = ByteArrayOutputStream()
        // Header
        val header = StringBuilder()
            .append("--").append(boundary).append(lineEnd)
            .append("Content-Disposition: form-data; name=\"file\"; filename=\"").append(file.name).append("\"").append(lineEnd)
            .append("Content-Type: application/zip").append(lineEnd)
            .append(lineEnd)
            .toString()

        output.write(header.toByteArray(StandardCharsets.UTF_8))
        output.write(fileBytes)
        output.write(lineEnd.toByteArray(StandardCharsets.UTF_8))

        // Footer
        val footer = "--$boundary--$lineEnd"
        output.write(footer.toByteArray(StandardCharsets.UTF_8))

        val body = output.toByteArray()

        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://mc-packs.net/"))
            .timeout(Duration.ofMinutes(2))
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .header("User-Agent", "LuminaHUD-PackUploader/1.0")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() in 200..399) {
            val responseBody = response.body()
            return parseResponse(responseBody, response.headers().firstValue("Location").orElse(null))
        } else {
            throw IllegalStateException("mc-packs.net Upload Failed (HTTP ${response.statusCode()}): ${response.body()}")
        }
    }

    private fun parseResponse(body: String, location: String?): McPacksUploadResult {
        // 1. ลอง parse แบบ JSON
        try {
            val json = JsonParser.parseString(body).asJsonObject
            val url = json.get("url")?.asString ?: json.get("download_url")?.asString
            val hash = json.get("hash")?.asString ?: json.get("sha1")?.asString
            if (url != null && hash != null) {
                return McPacksUploadResult(url, hash)
            }
        } catch (_: Exception) {
        }

        // 2. หากเป็น Redirect Location
        if (!location.isNullOrBlank()) {
            val hash = location.substringAfterLast("/").removeSuffix(".zip")
            return McPacksUploadResult(location, hash)
        }

        // 3. Regex หา download.mc-packs.net ใน HTML
        val urlRegex = Regex("""https://download\.mc-packs\.net/pack/[a-fA-F0-9]+\.zip""")
        val match = urlRegex.find(body)
        if (match != null) {
            val url = match.value
            val hash = url.substringAfterLast("/").removeSuffix(".zip")
            return McPacksUploadResult(url, hash)
        }

        throw IllegalStateException("ไม่สามารถแปลงข้อมูลลิงก์ดาวน์โหลดจาก mc-packs.net ได้")
    }
}

