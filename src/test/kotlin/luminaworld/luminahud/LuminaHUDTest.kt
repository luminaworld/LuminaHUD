package luminaworld.luminahud

import luminaworld.luminahud.config.HudDefinition
import luminaworld.luminahud.config.HudLayer
import luminaworld.luminahud.config.HudTextLayer
import luminaworld.luminahud.delivery.R2S3Uploader
import luminaworld.luminahud.font.FontRegistry
import luminaworld.luminahud.font.NegativeSpaceProvider
import luminaworld.luminahud.generator.SvgTranscoder
import luminaworld.luminahud.web.AuthTokenManager
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LuminaHUDTest {

    @Test
    fun testSvgTranscoder() {
        val transcoder = SvgTranscoder(antiAliasing = true)
        val svg = """
            <svg width="64" height="64" viewBox="0 0 64 64" xmlns="http://www.w3.org/2000/svg">
                <circle cx="32" cy="32" r="30" fill="#E74C3C" stroke="#2C3E50" stroke-width="4"/>
            </svg>
        """.trimIndent()

        val image = transcoder.transcodeSvg(svg)
        assertNotNull(image)
        assertEquals(64, image.width)
        assertEquals(64, image.height)
    }

    @Test
    fun testSvgGridTiling() {
        val transcoder = SvgTranscoder(antiAliasing = true)
        val svg = """
            <svg width="500" height="80" viewBox="0 0 500 80" xmlns="http://www.w3.org/2000/svg">
                <rect x="0" y="0" width="500" height="80" fill="#2ECC71"/>
            </svg>
        """.trimIndent()

        val image = transcoder.transcodeSvg(svg)
        assertEquals(500, image.width)
        assertEquals(80, image.height)

        val maxTileSize = 256
        val cols = Math.ceil(image.width.toDouble() / maxTileSize).toInt()
        val rows = Math.ceil(image.height.toDouble() / maxTileSize).toInt()
        assertEquals(2, cols)
        assertEquals(1, rows)
        assertEquals(2, cols * rows)
    }

    @Test
    fun testFontRegistry() {
        val registry = FontRegistry(0xE000)
        val glyph = registry.allocateGlyph("test_layer", "luminahud:hud/test.png", 8, 16)
        assertEquals('\uE000', glyph.unicode)
        assertEquals("luminahud:hud/test.png", glyph.texturePath)

        val nextGlyph = registry.allocateGlyph("test_layer_2", "luminahud:hud/test2.png", 8, 16)
        assertEquals('\uE001', nextGlyph.unicode)
        assertEquals(2, registry.totalRegistered)
    }

    @Test
    fun testNegativeSpaceProvider() {
        val provider = NegativeSpaceProvider(-1, -1024)
        val spaceJson = provider.toJsonProvider()
        assertTrue(spaceJson.has("type"))
        assertEquals("space", spaceJson.get("type").asString)
        assertTrue(spaceJson.has("advances"))

        val singleShift = provider.buildSpace(-8)
        assertTrue(singleShift.isNotEmpty())

        val complexShift = provider.buildSpace(-15)
        assertTrue(complexShift.isNotEmpty())
    }

    @Test
    fun testR2S3UploaderInstance() {
        val uploader = R2S3Uploader(
            endpoint = "https://a9eccff36d623b24fcc9d0ec7a2ffc87.r2.cloudflarestorage.com",
            bucketName = "luminaris-hud",
            accessKeyId = "b046c72af65dec83a1b80bcdfb026dfb",
            secretAccessKey = "31ec5d15a2be03e33449d42f02cd5d81c3e966be213d92976f9b199720561c94",
            publicBaseUrl = "https://pub-test.r2.dev"
        )
        assertNotNull(uploader)
    }

    @Test
    fun testAuthTokenManager() {
        val tokenManager = AuthTokenManager()
        val token = tokenManager.createToken(validityDurationMillis = 60_000L)
        assertTrue(token.isNotBlank())
        assertEquals(24, token.length)
        assertTrue(tokenManager.isValid(token))

        // Test Revocation
        tokenManager.revoke(token)
        assertFalse(tokenManager.isValid(token))

        // Test Expired Token
        val expiredToken = tokenManager.createToken(validityDurationMillis = -1000L)
        assertFalse(tokenManager.isValid(expiredToken))
    }

    @Test
    fun testHudDefinitionYamlSerialization() {
        val tempFile = File.createTempFile("hud_test", ".yml").apply { deleteOnExit() }

        val originalHud = HudDefinition(
            id = "test_rpg_hud",
            name = "Test RPG HUD",
            enabled = true,
            displayType = "ACTIONBAR",
            priority = 20,
            enabledWorlds = listOf("world", "world_nether"),
            anchor = "BOTTOM_CENTER",
            layers = listOf(
                HudLayer(
                    id = "bg",
                    type = "SVG",
                    file = "textures/hud/bg.svg",
                    ascent = 8,
                    height = 16,
                    xOffset = 0,
                    yOffset = 0,
                    scale = 1.0,
                    svgContent = "<svg></svg>"
                )
            ),
            textLayers = listOf(
                HudTextLayer(
                    id = "hp_text",
                    text = "%player_health%/20",
                    x = 10,
                    y = -2,
                    align = "CENTER",
                    scale = 1.0,
                    color = "#FF5555",
                    shadow = true,
                    condition = "%player_health% > 0"
                )
            )
        )

        originalHud.saveToYaml(tempFile)
        assertTrue(tempFile.exists())

        val loadedHud = HudDefinition.fromYaml(tempFile)
        assertNotNull(loadedHud)
        assertEquals("test_rpg_hud", loadedHud.id)
        assertEquals("Test RPG HUD", loadedHud.name)
        assertEquals("BOTTOM_CENTER", loadedHud.anchor)
        assertEquals(1, loadedHud.layers.size)
        assertEquals("bg", loadedHud.layers[0].id)
        assertEquals(1, loadedHud.textLayers.size)
        assertEquals("hp_text", loadedHud.textLayers[0].id)
        assertEquals("%player_health%/20", loadedHud.textLayers[0].text)
        assertEquals("CENTER", loadedHud.textLayers[0].align)
        assertEquals("#FF5555", loadedHud.textLayers[0].color)
        assertTrue(loadedHud.textLayers[0].shadow)
    }

    @Test
    fun testWebStudioResourcePackaged() {
        val stream = javaClass.getResourceAsStream("/web/studio.html")
        assertNotNull(stream, "studio.html should be accessible in classpath resources")
        val content = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        assertTrue(content.contains("LuminaHUD Web Studio"))
        assertTrue(content.contains("Figma SVG Importer"))
        assertTrue(content.contains("Minecraft Viewport Simulator"))
        assertTrue(content.contains("Save & Build HUD"))
    }

    @Test
    fun testCleanerThreadQueueIntegrity() {
        // ตรวจสอบว่า ReferenceQueue ของ Batik ทำงานและไม่เป็น null
        val queue = org.apache.batik.util.CleanerThread.getReferenceQueue()
        assertNotNull(queue, "Batik ReferenceQueue must never be null")

        val cleanerClass = org.apache.batik.util.CleanerThread::class.java
        val queueField = cleanerClass.getDeclaredField("queue").apply { isAccessible = true }
        assertNotNull(queueField.get(null), "CleanerThread.queue static field must be valid")
    }
}
