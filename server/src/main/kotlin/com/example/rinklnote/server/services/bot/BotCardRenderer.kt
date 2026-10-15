package com.example.rinklnote.server.services.bot

import com.example.rinklnote.server.services.Money
import kotlinx.serialization.json.*

/** 平台渲染只包含展示值和动作票据，不暴露登录凭据或账单备注。 */
object BotCardRenderer {
    private fun plain(text: String) = buildJsonObject { put("tag", "plain_text"); put("content", text) }
    private fun button(label: String, token: String, submit: Boolean = false) = buildJsonObject {
        put("tag", "button"); put("text", plain(label)); put("type", "primary")
        if (submit) { put("name", "submit"); put("form_action_type", "submit") }
        putJsonArray("behaviors") { add(buildJsonObject {
            put("type", "callback"); putJsonObject("value") { put("token", token) }
        }) }
    }

    private fun card(title: String, elements: List<JsonObject>) = buildJsonObject {
        put("schema", "2.0")
        putJsonObject("config") { put("update_multi", true) }
        putJsonObject("header") { put("template", "blue"); put("title", plain(title)) }
        putJsonObject("body") { put("elements", JsonArray(elements)) }
    }
    private fun markdown(text: String) = buildJsonObject { put("tag", "markdown"); put("content", text) }

    fun feishu(model: BotCard, message: String? = null): JsonObject = card(model.title,
        listOf(markdown(listOfNotNull(message, model.text).joinToString("\n"))) + model.buttons.map { button(it.label, it.token) })

    fun result(text: String): JsonObject = card("处理结果", listOf(markdown(text)))

    fun feishuEditForm(view: InteractionView, token: String): JsonObject {
        val bill = requireNotNull(view.bill)
        return card("修正这一笔", listOf(markdown(view.text), buildJsonObject {
            put("tag", "form"); put("name", "bill_edit")
            putJsonArray("elements") {
                add(buildJsonObject {
                    put("tag", "input"); put("name", "amount"); put("default_value", Money.format(bill.amountMinor))
                    put("label", plain("金额（元）")); put("required", true)
                })
                add(buildJsonObject {
                    put("tag", "select_static"); put("name", "category"); put("initial_option", bill.categoryId.toString())
                    put("placeholder", plain("请选择分类")); put("required", true)
                    putJsonArray("options") { view.categories.forEach { c -> add(buildJsonObject {
                        put("text", plain(c.name)); put("value", c.id.toString())
                    }) } }
                })
                add(button("保存修改", token, submit = true))
            }
        }))
    }

    fun feishuReminderForm(view: InteractionView, token: String): JsonObject {
        val budget = requireNotNull(view.budget)
        return card("日报提醒", listOf(buildJsonObject {
            put("tag", "form"); put("name", "reminders")
            putJsonArray("elements") {
                add(buildJsonObject {
                    put("tag", "select_static"); put("name", "enabled"); put("initial_option", budget.enabled.toString())
                    put("required", true); put("placeholder", plain("提醒状态"))
                    putJsonArray("options") { listOf("开启" to "true", "关闭" to "false").forEach { (label, value) ->
                        add(buildJsonObject { put("text", plain(label)); put("value", value) })
                    } }
                })
                add(buildJsonObject {
                    put("tag", "input"); put("name", "time"); put("required", true)
                    put("default_value", "%02d:%02d".format(budget.hour, budget.minute))
                    put("label", plain("提醒时间（HH:mm）"))
                })
                add(button("保存提醒", token, submit = true))
            }
        }))
    }

    fun qq(model: BotCard, msgId: String): JsonObject = buildJsonObject {
        put("msg_type", 2)
        if (msgId.isNotBlank()) put("msg_id", msgId)
        putJsonObject("markdown") { put("content", "# ${model.title}\n${model.text}") }
        putJsonObject("keyboard") { putJsonObject("content") {
            putJsonArray("rows") { model.buttons.take(25).chunked(5).forEach { row -> add(buildJsonObject {
                putJsonArray("buttons") { row.forEach { b -> add(buildJsonObject {
                    put("id", b.token.take(12))
                    putJsonObject("render_data") { put("label", b.label); put("visited_label", b.label); put("style", 1) }
                    putJsonObject("action") {
                        put("type", 1); put("data", b.token); put("unsupport_tips", "请使用小盘提供的文字入口")
                        putJsonObject("permission") { put("type", 0); putJsonArray("specify_user_ids") { add(model.actor) } }
                    }
                }) } }
            }) } }
        } }
    }

    fun qqFallback(model: BotCard): String = model.text + "\n" + model.buttons.joinToString("\n") {
        "${it.label}：回复「${it.label} ${it.token.take(12)}」"
    }
}
