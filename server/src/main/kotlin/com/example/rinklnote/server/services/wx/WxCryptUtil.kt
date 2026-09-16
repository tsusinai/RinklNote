package com.example.rinklnote.server.services.wx

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 微信系（企业微信 / 公众号）回调的验签、加解密与 XML 工具（C-W0 底座，零依赖手写）。
 *
 * 常量出处：调研文档《bot渠道机制调研与选型》3.2 / 4.2 节，与微信官方文档
 * 《加解密方案》（企微 90248 / 公众号消息加解密）一致：
 *  - 验签：`signature = SHA1(字典序排序(token, timestamp, nonce, encryptMsg) 后拼接)` 十六进制小写；
 *  - 对称加密：AES-256-CBC/PKCS7，Key = Base64Decode(EncodingAESKey + "=")（43 位编码 → 32 字节），
 *    **IV = Key 的前 16 字节**（与飞书「密文前 16 字节作 IV」不同，勿混用）；
 *    明文结构 = 16 字节随机串 + 4 字节 msg_len（网络序/大端）+ msg + receiveid；
 *  - PKCS7 填充分组按微信官方参考实现取 **32 字节**（PKCS7Encoder BLOCK_SIZE=32），
 *    因此解密用 NoPadding + 手工去填充，不能用 JCE 标准 PKCS5Padding（只认 16 字节分组）；
 *  - XML 载体只处理微信回调那种「一层结构」，CDATA 与纯文本值均支持。
 *
 * 边界标注（实施时对照官方文档核对）：被动回复的加密 XML 与本类 encrypt 输出同构；
 * 明文模式（订阅号选用的路线）不经过本类的加解密，只用 parseXml/buildXml 与验签。
 */
object WxCryptUtil {

    /** 验签 / 解密失败等错误（调用方按业务决定是忽略消息还是回错误包）。 */
    class WxCryptException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

    /** PKCS7 填充分组大小（微信官方参考实现取 32，非标准 AES 的 16）。 */
    private const val BLOCK_SIZE = 32

    private val random = SecureRandom()

    // ── 验签 ──

    /** SHA1 验签值：四个参数字典序排序后拼接再 SHA1，十六进制小写。 */
    fun signature(token: String, timestamp: String, nonce: String, encryptMsg: String): String {
        val joined = listOf(token, timestamp, nonce, encryptMsg).sorted().joinToString("")
        val digest = MessageDigest.getInstance("SHA-1").digest(joined.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** 验签比对（大小写不敏感；恒时比较防时序侧信道，安全评审修复）。 */
    fun verifySignature(
        signature: String,
        token: String,
        timestamp: String,
        nonce: String,
        encryptMsg: String
    ): Boolean {
        val expected = signature(token, timestamp, nonce, encryptMsg)
        // MessageDigest.isEqual 恒时比较（照 QQWebhookRoutes 写法）；两侧先统一小写保持既有语义
        return MessageDigest.isEqual(
            expected.lowercase().toByteArray(Charsets.UTF_8),
            signature.lowercase().toByteArray(Charsets.UTF_8)
        )
    }

    /**
     * 公众号明文模式验签（C-W2 订阅号用）：`signature = SHA1(字典序排序(token, timestamp, nonce) 拼接)`。
     * 与企微 / 安全模式的 4 参验签（[signature]）差异仅在「无密文参与」——实现上复用 4 参逻辑
     * 传空串：空串字典序恒排最先且不贡献字符，拼接结果与三参数官方规则逐字节一致（含参数为空的退化情形）。
     */
    fun signature3(token: String, timestamp: String, nonce: String): String =
        signature(token, timestamp, nonce, "")

    // ── 加解密 ──

    /** 解密结果：msg 为明文消息（XML 或 echostr），receiveId 为尾部校验串（企微 corpid / 公众号 appid）。 */
    data class DecryptedMessage(val message: String, val receiveId: String)

    /**
     * 解密微信回调密文。
     * @param encodingAesKey 后台配置的 43 位 EncodingAESKey
     * @param cipherBase64 密文（Base64，来自 XML 的 Encrypt 字段或 echostr 参数）
     */
    fun decrypt(encodingAesKey: String, cipherBase64: String): DecryptedMessage {
        val key = decodeAesKey(encodingAesKey)
        val data = try {
            Base64.getDecoder().decode(cipherBase64.trim())
        } catch (e: IllegalArgumentException) {
            throw WxCryptException("密文不是合法 Base64", e)
        }
        if (data.isEmpty() || data.size % 16 != 0) {
            throw WxCryptException("密文长度非法：${data.size}")
        }
        val plain = try {
            // NoPadding + 手工去填充：微信按 32 字节分组填 PKCS7，JCE 标准 PKCS5Padding 会误判
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(key.copyOfRange(0, 16)))
            cipher.doFinal(data)
        } catch (e: Exception) {
            throw WxCryptException("AES 解密失败（EncodingAESKey 或密文不匹配）", e)
        }

        // PKCS7 去填充（末字节 = 填充长度，取值 1..32）
        val pad = plain.last().toInt() and 0xFF
        if (pad < 1 || pad > BLOCK_SIZE || pad > plain.size) {
            throw WxCryptException("PKCS7 填充非法：$pad")
        }
        val unpadded = plain.copyOfRange(0, plain.size - pad)

        // 明文结构：16B 随机串 + 4B msg_len(网络序) + msg + receiveid
        if (unpadded.size < 20) throw WxCryptException("明文过短（${unpadded.size}）")
        val msgLen = ((unpadded[16].toLong() and 0xFF) shl 24) or
            ((unpadded[17].toLong() and 0xFF) shl 16) or
            ((unpadded[18].toLong() and 0xFF) shl 8) or
            (unpadded[19].toLong() and 0xFF)
        if (msgLen < 0 || 20 + msgLen > unpadded.size) {
            throw WxCryptException("msg_len 越界：$msgLen（明文 ${unpadded.size} 字节）")
        }
        val message = String(unpadded, 20, msgLen.toInt(), Charsets.UTF_8)
        val receiveId = String(unpadded, 20 + msgLen.toInt(), (unpadded.size - 20 - msgLen).toInt(), Charsets.UTF_8)
        return DecryptedMessage(message, receiveId)
    }

    /** 加密（解密逆过程）：随机 16B + msg_len(网络序) + msg + receiveid → PKCS7(32) → AES-CBC → Base64。 */
    fun encrypt(encodingAesKey: String, plainMsg: String, receiveId: String): String {
        val random16 = ByteArray(16).also { random.nextBytes(it) }
        return encryptWithRandom(encodingAesKey, random16, plainMsg.toByteArray(Charsets.UTF_8), receiveId)
    }

    /** 指定随机前缀的加密（单测构造确定性向量用；生产走 [encrypt]）。 */
    internal fun encryptWithRandom(encodingAesKey: String, random16: ByteArray, msgBytes: ByteArray, receiveId: String): String {
        require(random16.size == 16) { "随机前缀必须 16 字节" }
        val key = decodeAesKey(encodingAesKey)
        val receiveBytes = receiveId.toByteArray(Charsets.UTF_8)
        val msgLen = msgBytes.size

        val buf = ByteArray(16 + 4 + msgLen + receiveBytes.size)
        random16.copyInto(buf, 0)
        buf[16] = ((msgLen ushr 24) and 0xFF).toByte()
        buf[17] = ((msgLen ushr 16) and 0xFF).toByte()
        buf[18] = ((msgLen ushr 8) and 0xFF).toByte()
        buf[19] = (msgLen and 0xFF).toByte()
        msgBytes.copyInto(buf, 20)
        receiveBytes.copyInto(buf, 20 + msgLen)

        // PKCS7 填充：分组 32，整除时补一整个分组（官方参考实现行为）
        val padLen = BLOCK_SIZE - (buf.size % BLOCK_SIZE)
        val padded = buf.copyOf(buf.size + padLen)
        for (i in buf.size until padded.size) padded[i] = padLen.toByte()

        return try {
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(key.copyOfRange(0, 16)))
            Base64.getEncoder().encodeToString(cipher.doFinal(padded))
        } catch (e: Exception) {
            throw WxCryptException("AES 加密失败", e)
        }
    }

    /** EncodingAESKey（43 位）Base64 补位解码为 32 字节 AES key。 */
    private fun decodeAesKey(encodingAesKey: String): ByteArray {
        val key = try {
            Base64.getDecoder().decode(encodingAesKey.trim() + "=")
        } catch (e: IllegalArgumentException) {
            throw WxCryptException("EncodingAESKey 不是合法 Base64", e)
        }
        if (key.size != 32) throw WxCryptException("EncodingAESKey 解码后必须为 32 字节，实际 ${key.size}")
        return key
    }

    // ── 微型 XML 解析 / 组装（只处理微信回调的一层结构） ──

    /**
     * 解析一层 XML（`<xml><Tag>value</Tag>...</xml>`）为键值对。
     * 值支持 CDATA 与纯文本（纯文本做基本实体反转义）；不支持嵌套（微信回调用不到）。
     */
    fun parseXml(xml: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        // 先剥掉 <xml> 根标签，避免根节点把整段内容吞成一个值
        val body = xml.trim()
            .replace(Regex("""^\s*<\s*xml[^>]*>"""), "")
            .replace(Regex("""</\s*xml>\s*$"""), "")
        val tagRegex = Regex("""<([A-Za-z][A-Za-z0-9_]*)>(.*?)</\1>""", RegexOption.DOT_MATCHES_ALL)
        for (m in tagRegex.findAll(body)) {
            val name = m.groupValues[1]
            val raw = m.groupValues[2]
            val value = if (raw.startsWith("<![CDATA[") && raw.endsWith("]]>")) {
                // 还原 buildXml 对 CDATA 内 ]]> 的转义
                raw.removePrefix("<![CDATA[").removeSuffix("]]>").replace("]]]]><![CDATA[>", "]]>")
            } else {
                decodeEntities(raw)
            }
            result[name] = value
        }
        return result
    }

    /** 组装一层 XML，所有值走 CDATA（微信被动回复的常规形态）；值内 `]]>` 做标准转义。 */
    fun buildXml(fields: Map<String, String>): String = buildString {
        append("<xml>")
        for ((name, value) in fields) {
            append('<').append(name).append("><![CDATA[")
                .append(value.replace("]]>", "]]]]><![CDATA[>"))
                .append("]]></").append(name).append('>')
        }
        append("</xml>")
    }

    /** 基本实体反转义（微信纯文本值可能出现的五类）。 */
    private fun decodeEntities(s: String): String = s
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}
