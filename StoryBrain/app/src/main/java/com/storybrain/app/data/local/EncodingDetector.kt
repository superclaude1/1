package com.storybrain.app.data.local

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * 文本编码自动检测。
 * 网文 txt 大量是 GBK/GB18030 编码（尤其早年下载的资源），直接按 UTF-8 读会乱码。
 * 策略：
 *  1) 优先识别 BOM；
 *  2) 严格 UTF-8 校验通过 → UTF-8；
 *  3) 否则尝试 GB18030（GBK 超集，兼容 GBK 与繁体常用字）。
 */
object EncodingDetector {

    fun decode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        // 1) BOM
        val (bomCharset, consumed) = stripBom(bytes)
        if (bomCharset != null) {
            // UTF-8 BOM → 用 UTF-8；UTF-16 BE/LE BOM → 用对应 charset
            val cs = if (bomCharset.isEmpty()) StandardCharsets.UTF_8 else Charset.forName(bomCharset)
            return String(bytes, consumed, bytes.size - consumed, cs)
        }

        // 2) 严格 UTF-8 校验
        if (isStrictUtf8(bytes)) {
            return String(bytes, StandardCharsets.UTF_8)
        }
        // 3) 回退 GB18030
        return String(bytes, Charset.forName("GB18030"))
    }

    private fun stripBom(bytes: ByteArray): Pair<String?, Int> {
        // UTF-8 BOM: EF BB BF
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) return "" to 3
        // UTF-16 BE BOM: FE FF
        if (bytes.size >= 2 &&
            bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()
        ) return "UTF-16BE" to 2
        // UTF-16 LE BOM: FF FE
        if (bytes.size >= 2 &&
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()
        ) return "UTF-16LE" to 2
        return null to 0
    }

    /** 严格 UTF-8 校验：每个字节序列必须符合 UTF-8 编码规则 */
    private fun isStrictUtf8(bytes: ByteArray): Boolean {
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            val need: Int
            when {
                b <= 0x7F -> { i++; continue }
                b in 0xC2..0xDF -> need = 1
                b in 0xE0..0xEF -> need = 2
                b in 0xF0..0xF4 -> need = 3
                else -> return false
            }
            if (i + need >= bytes.size) return false
            for (k in 1..need) {
                if ((bytes[i + k].toInt() and 0xC0) != 0x80) return false
            }
            i += 1 + need
        }
        return true
    }

    /** 判断解码结果是否像“正常中文文本”（避免 GBK 解码出全乱码仍被采用） */
    fun looksLikeChinese(text: String): Boolean {
        if (text.isBlank()) return false
        val sample = text.take(2000)
        val cjk = sample.count { it.code in 0x4E00..0x9FFF }
        val replacement = sample.count { it == '\uFFFD' }
        // 替换字符多说明解码失败
        if (replacement > sample.length / 50) return false
        // CJK 占比 > 5% 视为中文文本
        return cjk * 20 > sample.length
    }
}
