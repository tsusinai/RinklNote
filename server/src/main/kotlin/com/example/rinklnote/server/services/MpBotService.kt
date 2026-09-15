package com.example.rinklnote.server.services

import com.example.rinklnote.server.tables.BotConfigTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

/**
 * 个人订阅号配置服务（C-W2）。
 *
 * 订阅号**没有任何主动推送能力**（客服消息 / 模板消息 / 订阅通知均需认证服务号，
 * 个人主体无法认证，见调研文档 4.5），因此本服务刻意保持极简：**无 HTTP 出口、无绑定码**——
 * 「登录」类指令在 MpMessageProcessor 里按「不支持」回复，绑定发码属可选项未实现。
 *
 * 配置存 bot_config KV 表，key（读写照 FeishuBotService 的 KV 模式；Web 管理卡片属 Phase E）：
 *  - mp_token：服务器配置的 Token（明文模式仅此一个必配 key）
 *  - mp_encoding_aes_key：可空——明文模式留空；后台若切「安全模式」则配置 43 位
 *    EncodingAESKey，验签加解密走 `services/wx/WxCryptUtil`（与企微同套底座）
 */
class MpBotService {
    private val logger = LoggerFactory.getLogger(MpBotService::class.java)

    companion object {
        private const val KEY_TOKEN = "mp_token"
        private const val KEY_AES_KEY = "mp_encoding_aes_key"
    }

    @Volatile private var token: String? = null
    @Volatile private var encodingAesKey: String? = null

    /** 是否完成接入配置（Token 非空）。 */
    fun isConfigured(): Boolean = !token.isNullOrBlank()

    fun configure(token: String, encodingAesKey: String? = null) {
        require(token.isNotBlank()) { "订阅号 Token 不能为空" }
        this.token = token
        this.encodingAesKey = encodingAesKey?.takeIf { it.isNotBlank() }
        logger.info("订阅号已配置（明文${if (this.encodingAesKey != null) "以外还配了加密 key（安全模式）" else "模式"}）")
    }

    /** 从 bot_config 表读配置（启动时调用；读失败仅告警，不阻塞启动）。 */
    fun loadFromDb() {
        try {
            transaction {
                fun read(key: String): String? = BotConfigTable.selectAll()
                    .where { BotConfigTable.key eq key }
                    .singleOrNull()?.get(BotConfigTable.value)
                val t = read(KEY_TOKEN)
                if (!t.isNullOrBlank()) {
                    configure(t, read(KEY_AES_KEY))
                }
            }
        } catch (e: Exception) {
            logger.warn("从数据库加载订阅号配置失败: ${e.message}")
        }
    }

    /** 两个 key 一次写库（aes_key 空串表示明文模式）。 */
    fun saveToDb(token: String, encodingAesKey: String = "") {
        transaction {
            listOf(
                KEY_TOKEN to token,
                KEY_AES_KEY to encodingAesKey
            ).forEach { (k, v) ->
                val existing = BotConfigTable.selectAll().where { BotConfigTable.key eq k }.singleOrNull()
                if (existing != null) {
                    BotConfigTable.update({ BotConfigTable.key eq k }) { it[BotConfigTable.value] = v }
                } else {
                    BotConfigTable.insert {
                        it[BotConfigTable.key] = k
                        it[BotConfigTable.value] = v
                    }
                }
            }
        }
        configure(token, encodingAesKey)
        logger.info("订阅号配置已写入数据库")
    }

    /** 管理端「是否已有保存过的配置」（看库不看内存）。 */
    fun hasSavedConfig(): Boolean = try {
        transaction {
            !BotConfigTable.selectAll()
                .where { BotConfigTable.key eq KEY_TOKEN }
                .singleOrNull()?.get(BotConfigTable.value).isNullOrBlank()
        }
    } catch (e: Exception) {
        false
    }

    fun getToken(): String? = token
    fun getEncodingAesKey(): String? = encodingAesKey
}
