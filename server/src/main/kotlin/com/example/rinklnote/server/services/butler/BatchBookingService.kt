package com.example.rinklnote.server.services.butler

import com.example.rinklnote.server.services.BillService
import com.example.rinklnote.server.services.nlu.NLUService

/**
 * 阶段 2 · 批量/多行记账（转发消息识别的落地形态）：QQ 官方平台拿不到合并转发的节点树，
 * 但转发/粘贴的文本会以多行正文到达。这里把多行文本逐行提取「分类 + 金额」，
 * ≥2 笔候选时回复编号清单并挂起待确认状态（[ButlerSession]，10 分钟 TTL），
 * 用户回「确认」全部入账、「取消」丢弃、「去掉2」剔除第 2 笔后再确认。
 *
 * TODO(阶段2-子代理B)：实现多行提取（纯函数）、待确认状态机、确认/取消指令与单测（见派工契约）。
 */
class BatchBookingService(
    private val billService: BillService,
    private val nluService: NLUService
) {

    /** 命中批量记账场景（多行候选 / 待确认状态指令）返回文案；否则 null 落回普通路由。 */
    suspend fun handle(inc: ButlerIncoming, text: String): String? {
        // TODO(阶段2)
        return null
    }
}
