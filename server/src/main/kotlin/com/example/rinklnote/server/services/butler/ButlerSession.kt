package com.example.rinklnote.server.services.butler

/**
 * 阶段 2 · 管家会话状态（进程内，重启丢弃可接受）：按用户挂起「批量记账待确认清单」
 * 等多轮交互的中间态，TTL 到期自动失效（用户重新发起新消息即覆盖旧态）。
 *
 * TODO(阶段2-子代理B)：按需扩展/调整内部结构；并发访问用线程安全容器（参考 QQBotService.bindCodes）。
 */
class ButlerSession {

    /** TODO(阶段2)：待确认清单状态 + put/get/clear + TTL 清理。 */
}
