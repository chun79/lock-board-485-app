package cn.tuodi.lockboard.core.testing

import cn.tuodi.lockboard.core.protocol.toHex
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ReportFormatter {
    private fun time(ms: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(ms))

    fun toText(r: TestReport): String = buildString {
        appendLine("12 路 485 锁控板测试报告（私有协议 + MODBUS RTU，固件 LOCK_12_MODBUS_TIMED_20261006）")
        appendLine("==========================")
        appendLine("总体结论：${r.verdict.label}${if (r.cancelled) "（测试被中止，结果不完整）" else ""}")
        if (!r.physicalTransport) appendLine("⚠ 本报告来自模拟器，只验证 App 逻辑，不代表实物验收。")
        appendLine("App 版本：${r.appVersion}")
        appendLine("开始：${time(r.startedAtMillis)}    结束：${time(r.finishedAtMillis)}")
        appendLine("链路：${r.transport}")
        appendLine("参数：${r.settingsSummary}")
        appendLine(
            "测试设置：人工确认=${if (r.plan.manualConfirm) "开" else "关"}，要求锁反馈=${if (r.plan.requireFeedback) "是" else "否"}，" +
                "成功率阈值 ${(r.plan.minSuccessRate * 100).toInt()}% / 压力 ${(r.plan.stressMinSuccessRate * 100).toInt()}%，" +
                "定时 ${r.plan.timedSeconds} s / 容差 ${r.plan.timerToleranceMs} ms",
        )
        appendLine("本轮通讯统计：${r.commStats}")
        if (r.possiblyEnergized.isNotEmpty()) {
            appendLine("‼ 以下通道无法经读回 0004 确认已关断，可能仍带电，请现场核实：${r.possiblyEnergized.joinToString()}")
        }
        for (b in r.boards) {
            appendLine()
            appendLine("【${b.boardName}】结论：${b.verdict.label}    私有版本：${b.privateVersion?.toHex() ?: "未读到"}    MODBUS 版本：${b.modbusVersion?.let { "%04X".format(it) } ?: "未读到"}")
            for (item in b.items) {
                appendLine("  ${item.id} ${item.name}：${item.verdict.label} —— ${item.summary}")
                item.metrics.forEach { (k, v) -> appendLine("      $k：$v") }
                for (s in item.steps) {
                    appendLine("      [${s.verdict.label}] ${s.text}${if (s.detail.isNotBlank()) "（${s.detail}）" else ""}")
                }
            }
        }
        appendLine()
        appendLine("判定说明：通过=回复、读回与反馈/人工观察均符合；未验证=通信正常但物理动作缺少反馈或观察证据（或回复归属不明）；")
        appendLine("警告=最终成功但有重试或可疑行为；失败=无回复、回复异常、读回或反馈不符、观察不符。")
        appendLine("读回 0004 是持续输出控制字，不代表物理电平；实际输出 = 持续输出 OR 脉冲输出。")
    }

    fun toJson(r: TestReport): JSONObject = JSONObject().apply {
        put("verdict", r.verdict.name)
        put("cancelled", r.cancelled)
        put("simulated", !r.physicalTransport)
        put("appVersion", r.appVersion)
        put("startedAt", time(r.startedAtMillis))
        put("finishedAt", time(r.finishedAtMillis))
        put("transport", r.transport)
        put("settings", r.settingsSummary)
        put("commStats", r.commStats)
        put("possiblyEnergized", JSONArray(r.possiblyEnergized.map { it.toString() }))
        put("plan", JSONObject().apply {
            put("manualConfirm", r.plan.manualConfirm)
            put("requireFeedback", r.plan.requireFeedback)
            put("communicationCount", r.plan.communicationCount)
            put("stressCount", r.plan.stressCount)
            put("minSuccessRate", r.plan.minSuccessRate)
            put("stressMinSuccessRate", r.plan.stressMinSuccessRate)
            put("timedSeconds", r.plan.timedSeconds)
            put("timerToleranceMs", r.plan.timerToleranceMs)
        })
        put("boards", JSONArray(r.boards.map { b ->
            JSONObject().apply {
                put("address", b.address)
                put("name", b.boardName)
                put("verdict", b.verdict.name)
                put("privateVersion", b.privateVersion?.let { JSONArray(it) } ?: JSONObject.NULL)
                put("modbusVersion", b.modbusVersion ?: JSONObject.NULL)
                put("items", JSONArray(b.items.map { item ->
                    JSONObject().apply {
                        put("id", item.id)
                        put("name", item.name)
                        put("verdict", item.verdict.name)
                        put("summary", item.summary)
                        put("metrics", JSONObject(item.metrics))
                        put("steps", JSONArray(item.steps.map { s ->
                            JSONObject().put("verdict", s.verdict.name).put("text", s.text).put("detail", s.detail)
                        }))
                    }
                }))
            }
        }))
    }
}
