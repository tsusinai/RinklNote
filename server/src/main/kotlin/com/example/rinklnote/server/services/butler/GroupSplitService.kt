package com.example.rinklnote.server.services.butler

import com.example.rinklnote.server.services.BillService

/**
 * 阶段 2 · 群聊 AA 分账：识别「@小盘 聚餐花了480 平摊」「AA 480 三个人」类消息，
 * 整数分安全拆分（除不尽余数分给前几人），生成 AA 结算单文案并落 AA 台账（催收可查）。
 *
 * TODO(阶段2-子代理B)：实现解析（纯函数）、分账算法、AA 台账表与结算指令（见派工契约）。
 */
class GroupSplitService(private val billService: BillService) {

    /** 命中 AA 意图返回结算单文案；未命中返回 null。 */
    suspend fun handle(inc: ButlerIncoming, text: String): String? {
        // TODO(阶段2)
        return null
    }
}
