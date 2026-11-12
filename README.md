# ✨ LuminaHUD (luminaworld.lumina.hud)

> **Clean Room SVG-to-Bitmap Font & Negative Space HUD Engine**  
> รองรับแพลตฟอร์ม Paper / Purpur / Folia (Minecraft 26.3+)

---

## 🚀 คุณสมบัติเด่น (Features)

1. **Vector SVG to Minecraft Bitmap Font Engine**:
   - เรนเดอร์กราฟิกเวกเตอร์ SVG ออกมาเป็น Bitmap PNG โดยอัตโนมัติด้วย Apache Batik Transcoder
   - ระบบ Auto Grid Tiling: ตัดแบ่งภาพขนาดใหญ่เป็นชิ้นส่วนย่อย (Tiles) เพื่อให้สอดคล้องกับข้อกำหนดฟอนต์ของ Minecraft
   - แมปเข้าสู่รหัส Private Use Area (PUA) อัตโนมัติ (เริ่มต้นที่ `\uE000`)
   - ระบบ **Negative Space Provider** (-1 ถึง -1024 px) สำหรับจัดเลเยอร์กราฟิกและข้อความซ้อนทับกันอย่างแม่นยำ

2. **LuminaHUD Web Studio (Javalin SPA)**:
   - ออกแบบหน้าจอ HUD แบบ Single-Page Application ในธีม Dark Mode Glassmorphism (`#02020A`, `#C351F5`)
   - **Minecraft Viewport Simulator**: จำลองหน้าจอเกมสัดส่วน 16:9 (1920x1080) พร้อมเส้น Safe Zone Guides 6 ทิศทาง
   - **Figma SVG Importer**: คัดลอก SVG จาก Figma มาวาง หรืออัปโหลดไฟล์ `.svg` พร้อมพรีวิวสด
   - **Dynamic Text & PlaceholderAPI Overlay**: ลากวาง (Drag & Drop) ข้อความ, ปรับขนาด, MiniMessage Gradient และเงาตัวอักษรแบบ Real-time
   - **Live Sync**: บันทึก HUD -> สั่งคอมไพล์ Resource Pack -> ส่งขึ้น Cloudflare R2 ทันที

3. **Pack Delivery Manager (การส่งต่อ Resource Pack)**:
   - **R2_S3**: อัปโหลดขึ้น Cloudflare R2 อัตโนมัติ
   - **MC_PACKS**: อัปโหลดขึ้น mc-packs.net ฟรี
   - **LOCAL_HOST**: เสิร์ฟไฟล์ Resource Pack ผ่าน Local Web Server (Javalin)
   - **EXTERNAL_OR_MERGE**: ใช้งานร่วมกับ ItemsAdder หรือ Nexo

---

## 🎮 คำสั่งการใช้งาน (Commands & Permissions)

| คำสั่ง | คำอธิบาย |
| :--- | :--- |
| `/luminahud editor` | สร้างลิงก์เข้าสู่ Web Studio พร้อม One-Time Token (อายุ 15 นาที) |
| `/luminahud pack` | สร้างและอัปโหลด Resource Pack (ไม่ส่งให้ผู้เล่นอัตโนมัติ) |
| `/luminahud pack applyto all` | สั่งสร้าง อัปโหลด และส่ง Resource Pack ให้ผู้เล่นทุกคนที่ออนไลน์ |
| `/luminahud pack applyto <player>` | สั่งสร้าง อัปโหลด และส่ง Resource Pack ให้ผู้เล่นที่ระบุ |
| `/luminahud apply [all/player]` | ส่ง Resource Pack ล่าสุดให้ผู้เล่นทันที |
| `/luminahud status` | ตรวจสอบสถานะ Web Server, Pack Delivery และจำนวน Glyph |
| `/luminahud reload` | รีโหลดคอนฟิกและไฟล์ HUD ทั้งหมด |

* **Permission:** `luminahud.admin` (ค่าเริ่มต้น: OP)

---

## 🛠️ การพัฒนาและการคอมไพล์ (Build & Test)

```bash
# คอมไพล์และรัน Unit Tests ทั้งหมด
./gradlew test

# ประกอบไฟล์ ShadowJar สำหรับนำไปใช้งานบนเซิร์ฟเวอร์
./gradlew shadowJar
```

ไฟล์ JAR ปลายทางจะอยู่ที่: `build/libs/LuminaHUD-1.0.0-SNAPSHOT.jar`

---

## 📄 License
ลิขสิทธิ์เฉพาะสำหรับเครือข่าย LuminaWorld (Luminaris Ecosystem)
