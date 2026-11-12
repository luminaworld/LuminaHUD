package luminaworld.luminahud.delivery

import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.time.Duration
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * ตัวอัปโหลดไฟล์ไปยัง Cloudflare R2 หรือ S3-Compatible Storage
 * ด้วยมาตรฐาน AWS Signature Version 4 (SigV4) แบบ Pure Kotlin ไม่ต้องพึ่งพา AWS SDK ที่ขนาดใหญ่
 */
class R2S3Uploader(
    private val endpoint: String,
    private val bucketName: String,
    private val accessKeyId: String,
    private val secretAccessKey: String,
    private val publicBaseUrl: String
) {

    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    private val region = "auto"
    private val service = "s3"

    /**
     * อัปโหลดไฟล์ ZIP ไปยัง Cloudflare R2
     * @return ลิงก์ดาวน์โหลดสาธารณะ (Public URL)
     */
    fun upload(file: File, objectKey: String = "LuminaHUD-Pack.zip"): String {
        val fileBytes = file.readBytes()
        val contentSha256 = sha256Hex(fileBytes)

        val cleanEndpoint = endpoint.removeSuffix("/")
        val uri = URI.create("$cleanEndpoint/$bucketName/$objectKey")
        val host = uri.host

        val now = Date()
        val amzDateFormat = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val dateStampFormat = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        val amzDate = amzDateFormat.format(now)
        val dateStamp = dateStampFormat.format(now)
        val contentType = "application/zip"

        // 1. Canonical Request
        val canonicalUri = "/$bucketName/$objectKey"
        val canonicalQueryString = ""
        val canonicalHeaders = "content-type:$contentType\nhost:$host\nx-amz-content-sha256:$contentSha256\nx-amz-date:$amzDate\n"
        val signedHeaders = "content-type;host;x-amz-content-sha256;x-amz-date"
        val canonicalRequest = "PUT\n$canonicalUri\n$canonicalQueryString\n$canonicalHeaders\n$signedHeaders\n$contentSha256"

        // 2. String to Sign
        val credentialScope = "$dateStamp/$region/$service/aws4_request"
        val stringToSign = "AWS4-HMAC-SHA256\n$amzDate\n$credentialScope\n${sha256Hex(canonicalRequest.toByteArray(StandardCharsets.UTF_8))}"

        // 3. Signature
        val signingKey = getSignatureKey(secretAccessKey, dateStamp, region, service)
        val signature = bytesToHex(hmacSha256(signingKey, stringToSign))

        // 4. Authorization Header
        val authorizationHeader = "AWS4-HMAC-SHA256 Credential=$accessKeyId/$credentialScope, SignedHeaders=$signedHeaders, Signature=$signature"

        val request = HttpRequest.newBuilder()
            .uri(uri)
            .timeout(Duration.ofMinutes(2))
            .header("Content-Type", contentType)
            .header("x-amz-date", amzDate)
            .header("x-amz-content-sha256", contentSha256)
            .header("Authorization", authorizationHeader)
            .PUT(HttpRequest.BodyPublishers.ofByteArray(fileBytes))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() in 200..299) {
            // หากมีการระบุ publicBaseUrl (เช่น https://pub-xxx.r2.dev หรือ https://pack.domain.com)
            return if (publicBaseUrl.isNotBlank()) {
                val base = publicBaseUrl.removeSuffix("/")
                "$base/$objectKey"
            } else {
                "$cleanEndpoint/$bucketName/$objectKey"
            }
        } else {
            throw IllegalStateException("Cloudflare R2 Upload Failed (HTTP ${response.statusCode()}): ${response.body()}")
        }
    }

    private fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return bytesToHex(digest.digest(data))
    }

    private fun hmacSha256(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(StandardCharsets.UTF_8))
    }

    private fun getSignatureKey(key: String, dateStamp: String, regionName: String, serviceName: String): ByteArray {
        val kSecret = ("AWS4$key").toByteArray(StandardCharsets.UTF_8)
        val kDate = hmacSha256(kSecret, dateStamp)
        val kRegion = hmacSha256(kDate, regionName)
        val kService = hmacSha256(kRegion, serviceName)
        return hmacSha256(kService, "aws4_request")
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format("%02x", b.toInt() and 0xFF))
        }
        return sb.toString()
    }
}

