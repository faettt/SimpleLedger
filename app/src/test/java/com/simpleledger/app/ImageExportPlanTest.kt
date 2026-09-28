package com.simpleledger.app

import com.simpleledger.app.logic.ImageExportPlan
import com.simpleledger.app.logic.ImageFileFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 贴图格式嗅探 / 解码门槛 / 导出命名的回归钉子（「贴图不压缩」批次）。
 *
 * 魔数表与门槛表是导入门控（importToPending 双检）的唯一真源：
 * - 嗅探错 = 该收的不收 / 不该收的收进「显示链解不出」的不可见数据；
 * - 门槛错 = HEIC/AVIF 在老设备上入库后本机解不出（导入即拒被破坏）；
 * - 文件名错 = GIF 导出成 .jpg 丢动画 / 同秒多张导出重名。
 */
class ImageExportPlanTest {

    // ---------- 魔数嗅探表 ----------

    @Test
    fun `detects formats by magic numbers`() {
        // JPEG：FF D8 FF（第三字节为 FF 开头区段，只钉前三字节）
        assertEquals(ImageFileFormat.JPEG, ImageExportPlan.detectFormat(hex("FFD8FFE000104A46494600")))
        assertEquals(ImageFileFormat.JPEG, ImageExportPlan.detectFormat(hex("FFD8FFEE")))
        // PNG：完整 8 字节签名 \x89PNG\r\n\x1a\n（IHDR 头跟在后面不影响判定）
        assertEquals(ImageFileFormat.PNG, ImageExportPlan.detectFormat(hex("89504E470D0A1A0A0000000D49484452")))
        // GIF：87a / 89a 两头都收（GIF 纠错口径：回入嗅探清单，minSdk 26 全收）
        assertEquals(ImageFileFormat.GIF, ImageExportPlan.detectFormat(hex("474946383761010001008000")))
        assertEquals(ImageFileFormat.GIF, ImageExportPlan.detectFormat(hex("474946383961010001008000")))
        // WEBP：RIFF 容器 + 偏移 8 的 WEBP 标记
        assertEquals(ImageFileFormat.WEBP, ImageExportPlan.detectFormat(hex("52494646240000005745425056503820")))
        // HEIC 四个 major brand 全认
        for (brand in listOf("heic", "heix", "hevc", "mif1")) {
            assertEquals(
                ImageFileFormat.HEIC,
                ImageExportPlan.detectFormat(hex("0000001866747970") + brand.toByteArray() + hex("0000000068656963")),
            )
        }
        // AVIF 两个 major brand
        for (brand in listOf("avif", "avis")) {
            assertEquals(
                ImageFileFormat.AVIF,
                ImageExportPlan.detectFormat(hex("0000001866747970") + brand.toByteArray() + hex("0000000061766966")),
            )
        }
        // BMP：两字节 "BM"
        assertEquals(ImageFileFormat.BMP, ImageExportPlan.detectFormat(hex("424D36040A0000000000")))
    }

    @Test
    fun `unrecognized or truncated bytes yield null`() {
        assertNull(ImageExportPlan.detectFormat(ByteArray(0)))
        assertNull(ImageExportPlan.detectFormat("随便一段非图片文本".toByteArray()))
        // ftyp 截断（major brand 不足 4 字节）不可凭半截判定
        assertNull(ImageExportPlan.detectFormat(hex("00000018667479706865")))
        // ftyp 但 brand 不在清单（mp42 = 视频）不收
        assertNull(ImageExportPlan.detectFormat(hex("00000018667479706D70343200000000")))
        // RIFF 但不是 WEBP（WAV 音频）不收
        assertNull(ImageExportPlan.detectFormat(hex("524946462400000057415645666D7420")))
    }

    // ---------- 本机解码门槛表 ----------

    @Test
    fun `min decode api table splits at heic and avif`() {
        assertEquals(26, ImageExportPlan.minDecodeApi(ImageFileFormat.JPEG))
        assertEquals(26, ImageExportPlan.minDecodeApi(ImageFileFormat.PNG))
        assertEquals(26, ImageExportPlan.minDecodeApi(ImageFileFormat.GIF))
        assertEquals(26, ImageExportPlan.minDecodeApi(ImageFileFormat.WEBP))
        assertEquals(26, ImageExportPlan.minDecodeApi(ImageFileFormat.BMP))
        // 分叉点：HEIC=28（Android 9）、AVIF=31（Android 12）
        assertEquals(28, ImageExportPlan.minDecodeApi(ImageFileFormat.HEIC))
        assertEquals(31, ImageExportPlan.minDecodeApi(ImageFileFormat.AVIF))
    }

    @Test
    fun `gates never fall below minSdk 26`() {
        for (format in ImageFileFormat.entries) {
            // 下限钉死：门槛不低于 minSdk（否则 minSdk 设备一张图都导不进）
            assertTrue("门槛低于 minSdk：$format", ImageExportPlan.minDecodeApi(format) >= 26)
        }
    }

    // ---------- 导出建议文件名 ----------

    @Test
    fun `suggested file name pins prefix stamp hash8 and real extension`() {
        val ms = 1_790_000_000_000L
        val name = ImageExportPlan.suggestedFileName(ms, "0123456789abcdef", ImageFileFormat.WEBP)
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(ms))
        assertEquals("简账_${stamp}_01234567.webp", name)
        // hash 全串不进名（防超长文件名）；GIF 必须是 .gif 才保得住动画
        assertFalse(name.contains("0123456789abcdef"))
    }

    @Test
    fun `suggested file name follows real format ext not legacy jpg alias`() {
        for (format in ImageFileFormat.entries) {
            val name = ImageExportPlan.suggestedFileName(0L, "deadbeefdeadbeef", format)
            assertTrue(
                "扩展名未跟真实格式：$format -> $name",
                name.endsWith("_deadbeef.${format.ext}"),
            )
            assertTrue("前缀缺失：$name", name.startsWith("简账_"))
        }
    }

    @Test
    fun `ext and mime table stays pinned for batch B`() {
        assertEquals("jpg", ImageFileFormat.JPEG.ext)
        assertEquals("image/jpeg", ImageFileFormat.JPEG.mime)
        assertEquals("png", ImageFileFormat.PNG.ext)
        assertEquals("image/png", ImageFileFormat.PNG.mime)
        assertEquals("gif", ImageFileFormat.GIF.ext)
        assertEquals("image/gif", ImageFileFormat.GIF.mime)
        assertEquals("webp", ImageFileFormat.WEBP.ext)
        assertEquals("image/webp", ImageFileFormat.WEBP.mime)
        assertEquals("heic", ImageFileFormat.HEIC.ext)
        assertEquals("image/heic", ImageFileFormat.HEIC.mime)
        assertEquals("avif", ImageFileFormat.AVIF.ext)
        assertEquals("image/avif", ImageFileFormat.AVIF.mime)
        assertEquals("bmp", ImageFileFormat.BMP.ext)
        assertEquals("image/bmp", ImageFileFormat.BMP.mime)
    }

    /** hex 字面量 → 字节数组（魔数测试辅助） */
    private fun hex(s: String): ByteArray =
        s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
