package com.example.rinklnote.server.services.butler

import com.example.rinklnote.server.services.BillService
import io.ktor.util.logging.Logger

/**
 * 阶段 1 · 识图入账：支付截图 / 外卖订单 / 纸质小票照片 → 视觉多模态模型提取
 * {商户, 实付金额, 交易日期, 分类建议} → 经 BillService 落库（source = QQ）→ 回执卡片。
 *
 * 配置约定（env，未配置时返回引导文案并记日志，绝不抛异常）：
 *   VISION_API_KEY / VISION_BASE_URL（默认 https://api.deepseek.com，需 OpenAI 兼容
 *   chat/completions 且模型支持图片输入）/ VISION_MODEL（如 qwen-vl-plus / glm-4v-flash）。
 *
 * 隐私红线：图片只在内存转发给视觉模型完成提取，不落盘、不进日志；日志只允许出现
 * 提取结果元数据（商户/金额/分类），绝不含图片内容或完整请求体。
 *
 * TODO(阶段1-子代理A)：实现视觉请求、结果 JSON 解析（纯函数可单测）、落库与回执（见派工契约）。
 */
class ReceiptOcrService(
    private val billService: BillService,
    private val log: Logger
) {

    /** 识图并入账；返回回执文案。图片缺失/未配置/识别失败/金额无效时返回引导文案（非 null）。 */
    suspend fun tryBook(inc: ButlerIncoming): String? {
        // TODO(阶段1)
        return "小盘还没学会看图记账（视觉模型未配置），先打字告诉我金额吧～"
    }

    fun shutdown() {
        // TODO(阶段1)：若持有 HttpClient，在此关闭（Application.kt 已接线调用）
    }
}
