package luminaworld.luminahud.generator

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import luminaworld.luminahud.LuminaHUDPlugin
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FileReader
import java.io.FileWriter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * จัดการการบีบอัด Resource Pack เป็นไฟล์ ZIP และการผสานข้อมูลเข้ากับปลั๊กอินภายนอก (Nexo, ItemsAdder, Oraxen)
 */
class PackMerger(private val plugin: LuminaHUDPlugin) {

    private val gson = GsonBuilder().setPrettyPrinting().create()

    /**
     * บีบอัดโฟลเดอร์ generated_pack เป็นไฟล์ ZIP
     */
    fun createZip(sourceDir: File, outputZip: File): Boolean {
        if (!sourceDir.exists()) return false

        try {
            if (outputZip.exists()) {
                outputZip.delete()
            }

            FileOutputStream(outputZip).use { fos ->
                ZipOutputStream(fos).use { zos ->
                    sourceDir.walkTopDown().forEach { file ->
                        if (file.isFile) {
                            val relativePath = sourceDir.toPath().relativize(file.toPath()).toString().replace("\\", "/")
                            val zipEntry = ZipEntry(relativePath)
                            zos.putNextEntry(zipEntry)
                            FileInputStream(file).use { fis ->
                                fis.copyTo(zos)
                            }
                            zos.closeEntry()
                        }
                    }
                }
            }
            return true
        } catch (e: Exception) {
            plugin.logger.severe("เกิดข้อผิดพลาดในการบีบอัด ZIP: ${e.message}")
            if (plugin.configManager.debug) {
                e.printStackTrace()
            }
            return false
        }
    }

    /**
     * ผสาน Resource Pack ไปยังปลั๊กอินภายนอก
     */
    fun autoMerge(sourceDir: File): Boolean {
        if (!plugin.configManager.autoMergeEnabled) return false

        val targetDir = resolveTargetDirectory() ?: run {
            plugin.logger.warning("[Auto-Merge] ไม่พบไดเรกทอรีเป้าหมายสำหรับ ${plugin.configManager.autoMergeTargetPlugin}")
            return false
        }

        try {
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }

            // คัดลอกและผสานไฟล์
            sourceDir.walkTopDown().forEach { sourceFile ->
                val relativePath = sourceDir.toPath().relativize(sourceFile.toPath()).toString()
                val destFile = File(targetDir, relativePath)

                if (sourceFile.isDirectory) {
                    if (!destFile.exists()) destFile.mkdirs()
                } else if (sourceFile.isFile) {
                    // หากเป็นไฟล์ font default.json ให้ผสาน providers เข้าด้วยกัน
                    if (sourceFile.name == "default.json" && destFile.exists()) {
                        mergeFontJson(sourceFile, destFile)
                    } else {
                        destFile.parentFile?.mkdirs()
                        sourceFile.copyTo(destFile, overwrite = true)
                    }
                }
            }

            plugin.logger.info("[Auto-Merge] ผสาน Resource Pack ไปยัง ${targetDir.absolutePath} เรียบร้อยแล้ว")
            return true
        } catch (e: Exception) {
            plugin.logger.severe("[Auto-Merge] เกิดข้อผิดพลาดในการผสานไฟล์: ${e.message}")
            if (plugin.configManager.debug) {
                e.printStackTrace()
            }
            return false
        }
    }

    private fun resolveTargetDirectory(): File? {
        val serverPluginsDir = plugin.dataFolder.parentFile ?: return null

        return when (plugin.configManager.autoMergeTargetPlugin.uppercase()) {
            "NEXO" -> File(serverPluginsDir, "Nexo/pack")
            "ITEMSADDER" -> File(serverPluginsDir, "ItemsAdder/contents/luminahud/resourcepack")
            "ORAXEN" -> File(serverPluginsDir, "Oraxen/pack")
            "CUSTOM" -> {
                val path = plugin.configManager.customTargetPath
                if (path.isNotBlank()) File(path) else null
            }
            else -> null
        }
    }

    private fun mergeFontJson(sourceJsonFile: File, targetJsonFile: File) {
        try {
            val sourceObj = JsonParser.parseReader(FileReader(sourceJsonFile)).asJsonObject
            val targetObj = JsonParser.parseReader(FileReader(targetJsonFile)).asJsonObject

            val sourceProviders = sourceObj.getAsJsonArray("providers") ?: JsonArray()
            val targetProviders = targetObj.getAsJsonArray("providers") ?: JsonArray()

            // เพิ่ม provider จาก source เข้าไปใน target หากยังไม่มี
            val mergedProviders = JsonArray()
            targetProviders.forEach { mergedProviders.add(it) }

            sourceProviders.forEach { srcProvider ->
                // ป้องกันการใส่ provider ซ้ำ
                if (!mergedProviders.contains(srcProvider)) {
                    mergedProviders.add(srcProvider)
                }
            }

            targetObj.add("providers", mergedProviders)

            FileWriter(targetJsonFile).use { writer ->
                gson.toJson(targetObj, writer)
            }
        } catch (e: Exception) {
            plugin.logger.warning("ไม่สามารถผสาน font JSON ได้ สลับไปใช้การเขียนทับ: ${e.message}")
            sourceJsonFile.copyTo(targetJsonFile, overwrite = true)
        }
    }
}

