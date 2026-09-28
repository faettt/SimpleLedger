package com.simpleledger.app.logic

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 贴图文件格式（「贴图不压缩」批次的嗅探目标清单）。
 *
 * 旧管线把一切图片重压缩成 JPEG(88)，格式语义可由扩展名兜底；新管线对图片
 * **原字节透传**，真实格式只活在字节里——由 [ImageExportPlan.detectFormat] 魔数嗅探给出，
 * 由 [ImageExportPlan.minDecodeApi] 的门槛表决定本机能不能解。
 *
 * 兼容定案（扩展名不进内容寻址路径）：盘上恒为 `filesDir/images/<sha256>.jpg`，
 * `.jpg` 是历史假名——存量 JPEG、跨设备同步（按 contentHash 单参重建路径）、
 * 备份 zip 全部零迁移；代价是假名可能与真实格式不符，消费方（导出命名、格式判断）
 * 一律以 [ImageExportPlan.detectFormat] 的嗅探结果为准，不得信任扩展名。
 */
enum class ImageFileFormat(val ext: String, val mime: String) {
    /** `FF D8 FF` */
    JPEG("jpg", "image/jpeg"),

    /** `\x89PNG\r\n\x1a\n` 完整 8 字节签名 */
    PNG("png", "image/png"),

    /** `GIF87a` / `GIF89a` 两头都收（minSdk 26 全收：旧管线本就把 GIF 解首帧静态化收下，
     *  新管线原字节保留动画语义，Coil 无 coil-gif 依赖时显示静态首帧，观感与旧管线一致；
     *  导出相册带 image/gif 与 .gif，比旧版静态化只多不少） */
    GIF("gif", "image/gif"),

    /** `RIFF` 容器 + 偏移 8 的 `WEBP` 标记 */
    WEBP("webp", "image/webp"),

    /** ISO BMFF：`ftyp` + major brand ∈ {heic, heix, hevc, mif1}（系统 HEIF 解码链 Android 9 起） */
    HEIC("heic", "image/heic"),

    /** ISO BMFF：`ftyp` + major brand ∈ {avif, avis}（系统 AVIF 解码链 Android 12 起） */
    AVIF("avif", "image/avif"),

    /** 两字节 `BM` */
    BMP("bmp", "image/bmp"),
}

/**
 * 贴图导入 / 导出的格式纯逻辑（无 Android 依赖，JVM 可测）。
 *
 * 三件事：
 * - [detectFormat]：魔数嗅探，只认文件头不认扩展名（待入库文件与内容寻址假名都不带真实扩展名）；
 * - [minDecodeApi]：本机解码门槛表，导入门控第二检，与嗅探双检联用——门槛不满足
 *   「导入即拒」，杜绝「入库成功但本机显示链解不出」的不可见数据；
 * - [suggestedFileName]：导出建议文件名（相册里的名字用真实格式扩展名，不用盘上假名）。
 */
object ImageExportPlan {

    /**
     * 魔数嗅探图片格式；识别不出返回 null（调用方按「图片无法解码」既有失败路径拒绝）。
     *
     * 注意：只按 major brand 判定 ftyp 容器，不展开 compatible brands 清单——
     * mif1 通用 HEIF 容器按 HEIC 处理（真 AVIF 若标 mif1，由 [minDecodeApi] 的
     * API 门槛兜住本机解不出的情形，宁可多识别、不漏收）。
     */
    fun detectFormat(bytes: ByteArray): ImageFileFormat? {
        val size = bytes.size
        return when {
            // JPEG：FF D8 FF（第三字节起为 FF 开头的区段，钉前三字节足够）
            size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() ->
                ImageFileFormat.JPEG
            // PNG：完整 8 字节签名 \x89PNG\r\n\x1a\n
            size >= 8 && PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] } ->
                ImageFileFormat.PNG
            // GIF：87a / 89a 两头
            size >= 6 && (asciiAt(bytes, 0, "GIF87a") || asciiAt(bytes, 0, "GIF89a")) ->
                ImageFileFormat.GIF
            // WEBP：RIFF 容器 + 偏移 8 的 WEBP 标记（WAV 等其它 RIFF 子类不收）
            size >= 12 && asciiAt(bytes, 0, "RIFF") && asciiAt(bytes, 8, "WEBP") ->
                ImageFileFormat.WEBP
            // HEIC / AVIF：ISO BMFF，偏移 4 为 "ftyp"、偏移 8 起为 major brand
            size >= 12 && asciiAt(bytes, 4, "ftyp") && HEIC_BRANDS.any { asciiAt(bytes, 8, it) } ->
                ImageFileFormat.HEIC
            size >= 12 && asciiAt(bytes, 4, "ftyp") && AVIF_BRANDS.any { asciiAt(bytes, 8, it) } ->
                ImageFileFormat.AVIF
            // BMP：两字节 "BM"
            size >= 2 && asciiAt(bytes, 0, "BM") ->
                ImageFileFormat.BMP
            else -> null
        }
    }

    /**
     * 本机解码门槛（minSdk 26 = Android 8.0）：
     * - JPEG / PNG / GIF / WEBP / BMP：Skia 自带解码，全 minSdk 收；
     * - HEIC：HEIF 解码链 Android 9（API 28）起——取 28 而非 27 偏保守，拒收侧安全；
     * - AVIF：Android 12（API 31）起；<31 拒与旧 BitmapFactory 行为一致（旧管线同样
     *   解不出 AVIF），31+ 收、无升级回退。
     */
    fun minDecodeApi(format: ImageFileFormat): Int = when (format) {
        ImageFileFormat.HEIC -> 28
        ImageFileFormat.AVIF -> 31
        else -> 26
    }

    /**
     * 导出建议文件名：`简账_时间戳_hash8.<真实扩展名>`（openQuestions ② 默认口径，
     * 时间戳取本地时区 `yyyyMMdd_HHmmss`）。
     *
     * 用真实格式扩展名而非盘上假名 `.jpg`——GIF 导出后必须是 .gif 才保得住动画；
     * hash 前 8 位进名避免同一秒导出多张时重名，也避免全 64 位 hex 撑长文件名。
     */
    fun suggestedFileName(epochMillis: Long, contentHashHex: String, format: ImageFileFormat): String {
        val stamp = FILE_NAME_STAMP
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(epochMillis))
        return "${NAME_PREFIX}_${stamp}_${contentHashHex.take(HASH_PREFIX_LEN)}.${format.ext}"
    }

    private val FILE_NAME_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

    private const val NAME_PREFIX = "简账"
    private const val HASH_PREFIX_LEN = 8

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    private val HEIC_BRANDS = listOf("heic", "heix", "hevc", "mif1")
    private val AVIF_BRANDS = listOf("avif", "avis")

    /** 偏移处 ASCII 匹配（越界视为不匹配，绝不越界读） */
    private fun asciiAt(bytes: ByteArray, offset: Int, text: String): Boolean {
        if (offset + text.length > bytes.size) return false
        for (i in text.indices) {
            if (bytes[offset + i] != text[i].code.toByte()) return false
        }
        return true
    }
}
