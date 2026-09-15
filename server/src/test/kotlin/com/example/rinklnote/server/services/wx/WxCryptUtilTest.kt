package com.example.rinklnote.server.services.wx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 微信系加解密底座（C-W0）单测：自造向量 + 独立计算的 SHA1 向量，不用真实线上密钥。
 * SHA1 验签向量由外部工具（node crypto）按同一官方拼接规则独立算出，避免循环验证。
 */
class WxCryptUtilTest {

    // 43 位 EncodingAESKey（Base64 补 "=" 后解码为 32 字节 AES key）
    private val aesKey = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFG"

    init {
        // 防手滑：字面量必须恰好 43 位
        assertEquals(43, aesKey.length)
    }

    // ── 验签 ──

    @Test
    fun `验签命中独立计算的官方拼接顺序向量`() {
        // 外部独立计算（node crypto）：sorted(token,timestamp,nonce,encrypt) 拼接后 SHA1
        // token=rinklToken, ts=1726400000, nonce=nonce123456, encrypt=P37w+VZImNgPEO1RBhJ6RtKl7n6zymIbEG1pReEzghk=
        val sig = WxCryptUtil.signature(
            "rinklToken", "1726400000", "nonce123456", "P37w+VZImNgPEO1RBhJ6RtKl7n6zymIbEG1pReEzghk="
        )
        assertEquals("803cd359e42e2f6227936d4d0d9243d96ea6c96c", sig)
        assertTrue(
            WxCryptUtil.verifySignature(
                "803CD359E42E2F6227936D4D0D9243D96EA6C96C", // 大小写不敏感
                "rinklToken", "1726400000", "nonce123456", "P37w+VZImNgPEO1RBhJ6RtKl7n6zymIbEG1pReEzghk="
            )
        )
    }

    @Test
    fun `验签参数被篡改则不通过`() {
        val encrypt = "P37w+VZImNgPEO1RBhJ6RtKl7n6zymIbEG1pReEzghk="
        val sig = WxCryptUtil.signature("rinklToken", "1726400000", "nonce123456", encrypt)
        assertFalse(WxCryptUtil.verifySignature(sig, "rinklToken", "1726400001", "nonce123456", encrypt))
        assertFalse(WxCryptUtil.verifySignature(sig, "otherToken", "1726400000", "nonce123456", encrypt))
        assertFalse(WxCryptUtil.verifySignature(sig, "rinklToken", "1726400000", "nonce123456", encrypt + "x"))
    }

    @Test
    fun `三参数明文验签命中独立计算的官方拼接顺序向量`() {
        // 公众号明文模式：signature = SHA1(sorted(token,timestamp,nonce) 拼接)。
        // 外部独立计算（shell sha1sum）：sorted → "1726400000" + "nonce123456" + "rinklToken"
        val sig = WxCryptUtil.signature3("rinklToken", "1726400000", "nonce123456")
        assertEquals("51d328455886414e4808503a7b740f30835ef033", sig)
        // 与 4 参验签传空串严格一致（空串字典序恒最先且零宽）
        assertEquals(WxCryptUtil.signature("rinklToken", "1726400000", "nonce123456", ""), sig)
    }

    // ── 加解密 ──

    @Test
    fun `加解密往返保持明文与receiveid`() {
        val msg = """<xml><ToUserName><![CDATA[wwcorp]]></ToUserName><Content><![CDATA[午餐20元]]></Content></xml>"""
        val cipher = WxCryptUtil.encrypt(aesKey, msg, "ww58c7d2d24a123456")
        val decrypted = WxCryptUtil.decrypt(aesKey, cipher)
        assertEquals(msg, decrypted.message)
        assertEquals("ww58c7d2d24a123456", decrypted.receiveId)
    }

    @Test
    fun `receiveid 为空也能往返`() {
        val cipher = WxCryptUtil.encrypt(aesKey, "plain text", "")
        val decrypted = WxCryptUtil.decrypt(aesKey, cipher)
        assertEquals("plain text", decrypted.message)
        assertEquals("", decrypted.receiveId)
    }

    @Test
    fun `错误 EncodingAESKey 解密报错`() {
        val cipher = WxCryptUtil.encrypt(aesKey, "secret", "corpid")
        val wrongKey = "zyxwvutsrqponmlkjihgfedcba0123456789AB" // 同为 43 位但内容不同
        try {
            WxCryptUtil.decrypt(wrongKey, cipher)
            org.junit.Assert.fail("应抛出 WxCryptException")
        } catch (e: WxCryptUtil.WxCryptException) {
            // 期望路径：padding/格式校验失败
        }
        // 非法长度（解码后不足 32 字节）
        try {
            WxCryptUtil.decrypt("short-key", cipher)
            org.junit.Assert.fail("短 key 应抛出 WxCryptException")
        } catch (e: WxCryptUtil.WxCryptException) {
        }
    }

    @Test
    fun `msg_len 越界解密报错`() {
        // 手工构造「msg_len 与实际明文不符」的密文：16B 随机 + 4B 越界 msg_len + msg + receiveid
        val key = java.util.Base64.getDecoder().decode(aesKey + "=")
        assertEquals(32, key.size)
        val raw = ByteArray(16 + 4 + 10 + 6)
        java.security.SecureRandom().nextBytes(raw)
        val fakeLen = 99_999
        raw[16] = ((fakeLen ushr 24) and 0xFF).toByte()
        raw[17] = ((fakeLen ushr 16) and 0xFF).toByte()
        raw[18] = ((fakeLen ushr 8) and 0xFF).toByte()
        raw[19] = (fakeLen and 0xFF).toByte()
        "msg".toByteArray().copyInto(raw, 20)
        "corpid".toByteArray().copyInto(raw, 26)
        val padLen = 32 - (raw.size % 32)
        val padded = raw.copyOf(raw.size + padLen)
        for (i in raw.size until padded.size) padded[i] = padLen.toByte()
        val cipher = java.util.Base64.getEncoder().encodeToString(
            javax.crypto.Cipher.getInstance("AES/CBC/NoPadding").apply {
                init(
                    javax.crypto.Cipher.ENCRYPT_MODE,
                    javax.crypto.spec.SecretKeySpec(key, "AES"),
                    javax.crypto.spec.IvParameterSpec(key.copyOfRange(0, 16))
                )
            }.doFinal(padded)
        )
        try {
            WxCryptUtil.decrypt(aesKey, cipher)
            org.junit.Assert.fail("msg_len 越界应抛出 WxCryptException")
        } catch (e: WxCryptUtil.WxCryptException) {
            assertTrue(e.message!!.contains("msg_len"))
        }
    }

    // ── XML ──

    @Test
    fun `XML 组装解析往返含特殊字符`() {
        val fields = linkedMapOf(
            "ToUserName" to "toUser",
            "FromUserName" to "fromUser",
            "CreateTime" to "1726400000",
            "MsgType" to "text",
            "Content" to "你好<世界]]>结束&更多"
        )
        val xml = WxCryptUtil.buildXml(fields)
        assertEquals(fields, WxCryptUtil.parseXml(xml))
    }

    @Test
    fun `parseXml 支持 CDATA 与纯文本混排并反转义实体`() {
        val xml = "<xml><ToUserName><![CDATA[ww企业]]></ToUserName>" +
            "<MsgType>text</MsgType>" +
            "<Content>&lt;你好&gt; &amp; &quot;world&quot;</Content></xml>"
        val parsed = WxCryptUtil.parseXml(xml)
        assertEquals("ww企业", parsed["ToUserName"])
        assertEquals("text", parsed["MsgType"])
        assertEquals("<你好> & \"world\"", parsed["Content"])
    }
}
