package cn.tuodi.lockboard.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.tuodi.lockboard.app.AppController
import cn.tuodi.lockboard.app.LinkStatus
import cn.tuodi.lockboard.core.config.ChannelType
import cn.tuodi.lockboard.core.testing.ReportFormatter
import cn.tuodi.lockboard.core.testing.TestPlan

@Composable
fun TestScreen(controller: AppController) {
    val cfg by controller.config.collectAsStateWithLifecycle()
    val link by controller.link.collectAsStateWithLifecycle()
    val scan by controller.scan.collectAsStateWithLifecycle()
    val board by controller.board.collectAsStateWithLifecycle()
    val test by controller.test.collectAsStateWithLifecycle()
    val endurance by controller.endurance.collectAsStateWithLifecycle()
    val armed by controller.armed.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val plan = cfg.plan
    val connected = link.status == LinkStatus.CONNECTED
    val busy = test.running || endurance.running
    var allBoards by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<List<String>?>(null) }
    var showFull by remember { mutableStateOf(false) }

    fun setPlan(t: (TestPlan) -> TestPlan) = controller.updateConfig { it.copy(plan = t(it.plan).validated()) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        SectionCard("测试对象") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = !allBoards, onClick = { allBoards = false })
                Text("当前板（地址 ${board.address}）")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = allBoards, onClick = { allBoards = true }, enabled = scan.online.isNotEmpty())
                Text("扫描到的全部在线板（${scan.online.joinToString(",") { it.address.toString() }.ifEmpty { "请先扫描" }}）")
            }
        }

        SectionCard("测试项") {
            Text("通信与一致性", fontWeight = FontWeight.Bold)
            LabeledCheckbox("T1 双协议通信：0x01 / MODBUS 03 读 0001", plan.communication, enabled = !busy) { v -> setPlan { it.copy(communication = v) } }
            LabeledCheckbox("T2 反馈一致性：0x12、12 路 0x11、MODBUS 02、03(0002)", plan.consistency, enabled = !busy) { v -> setPlan { it.copy(consistency = v) } }
            LabeledCheckbox("T9 私有异常帧：坏校验/半帧/杂散前缀/不支持命令", plan.robustness, enabled = !busy) { v -> setPlan { it.copy(robustness = v) } }
            LabeledCheckbox("M7 MODBUS 异常响应 01/02/03 与原子性", plan.modbusExceptions, enabled = !busy) { v -> setPlan { it.copy(modbusExceptions = v) } }
            LabeledCheckbox("T10 双协议交替压力：0x12 / 02", plan.stress, enabled = !busy) { v -> setPlan { it.copy(stress = v) } }
            Text("脉冲开锁", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
            LabeledCheckbox("T3 私有 0x10 逐路开锁", plan.unlockEach, enabled = !busy) { v -> setPlan { it.copy(unlockEach = v) } }
            LabeledCheckbox("M3 MODBUS 05 逐路开锁", plan.modbusUnlockEach, enabled = !busy) { v -> setPlan { it.copy(modbusUnlockEach = v) } }
            LabeledCheckbox("T4 私有 0x15 逐路开多个", plan.unlockMany, enabled = !busy) { v -> setPlan { it.copy(unlockMany = v) } }
            LabeledCheckbox("M4 MODBUS 0F 逐路开多个", plan.modbusUnlockMany, enabled = !busy) { v -> setPlan { it.copy(modbusUnlockMany = v) } }
            LabeledCheckbox("T5 私有 0x18 同时开", plan.unlockSimultaneous, enabled = !busy) { v -> setPlan { it.copy(unlockSimultaneous = v) } }
            LabeledCheckbox("M5 MODBUS 06 写 0006 同时开", plan.modbusSimultaneous, enabled = !busy) { v -> setPlan { it.copy(modbusSimultaneous = v) } }
            LabeledCheckbox("T6 私有 0x14 开全部（脉冲全部 12 路）", plan.unlockAll, enabled = !busy) { v -> setPlan { it.copy(unlockAll = v) } }
            Text("输出（仅灯/继电器通道，读回 0004 验证）", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
            LabeledCheckbox("T7 私有 0x16 持续输出开/关", plan.outputSingle, enabled = !busy) { v -> setPlan { it.copy(outputSingle = v) } }
            LabeledCheckbox("T8 私有 0x1A 整体输出（0 位撤销）", plan.outputMulti, enabled = !busy) { v -> setPlan { it.copy(outputMulti = v) } }
            LabeledCheckbox("T11 私有 0x16 定时输出", plan.outputTimed, enabled = !busy) { v -> setPlan { it.copy(outputTimed = v) } }
            LabeledCheckbox("M6 MODBUS 06/10 写 0004、10 写 0008（持续与定时）", plan.modbusOutputs, enabled = !busy) { v -> setPlan { it.copy(modbusOutputs = v) } }
            LabeledCheckbox("X1/X2 跨协议定时：重新定时、独立到期、0x1A 取消定时", plan.crossProtocolTimers, enabled = !busy) { v -> setPlan { it.copy(crossProtocolTimers = v) } }
        }

        SectionCard("判定与参数") {
            LabeledSwitch("人工确认物理动作（锁是否弹开、负载是否通电）", plan.manualConfirm, enabled = !busy) { v -> setPlan { it.copy(manualConfirm = v) } }
            LabeledSwitch("要求锁反馈确认开锁（未接反馈线的通道在配置页取消“有反馈”）", plan.requireFeedback, enabled = !busy) { v -> setPlan { it.copy(requireFeedback = v) } }
            LabeledSwitch("允许 0x14 脉冲灯/继电器通道", plan.allowPulseOnOutputs, enabled = !busy) { v -> setPlan { it.copy(allowPulseOnOutputs = v) } }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                IntField("T1 次数", plan.communicationCount, 1..10_000, Modifier.weight(1f)) { v -> setPlan { it.copy(communicationCount = v) } }
                IntField("T10 次数", plan.stressCount, 1..100_000, Modifier.weight(1f)) { v -> setPlan { it.copy(stressCount = v) } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                IntField("成功率阈值 %", (plan.minSuccessRate * 100).toInt(), 0..100, Modifier.weight(1f)) { v -> setPlan { it.copy(minSuccessRate = v / 100.0) } }
                IntField("压力阈值 %", (plan.stressMinSuccessRate * 100).toInt(), 0..100, Modifier.weight(1f)) { v -> setPlan { it.copy(stressMinSuccessRate = v / 100.0) } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                IntField("动作后读回 ms", plan.settleMs, 0..10_000, Modifier.weight(1f)) { v -> setPlan { it.copy(settleMs = v) } }
                IntField("逐路间隔 ms", plan.unlockIntervalMs, 0..60_000, Modifier.weight(1f)) { v -> setPlan { it.copy(unlockIntervalMs = v) } }
            }
            IntField("等待关门最长 ms", plan.waitClosedMs, 0..120_000, Modifier.fillMaxWidth()) { v -> setPlan { it.copy(waitClosedMs = v) } }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                IntField("定时秒数", plan.timedSeconds, 1..600, Modifier.weight(1f)) { v -> setPlan { it.copy(timedSeconds = v) } }
                IntField("定时容差 ms", plan.timerToleranceMs, 0..60_000, Modifier.weight(1f)) { v -> setPlan { it.copy(timerToleranceMs = v) } }
            }
            Text("T5 同时开的通道（不选 = 前 3 个电控锁通道）：")
            ChannelSelector(plan.simultaneousChannels.toSet()) { s -> setPlan { it.copy(simultaneousChannels = s.sorted()) } }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { preview = controller.previewTest(controller.testTargets(allBoards)) },
                enabled = connected && !busy && !scan.running && (!allBoards || scan.online.isNotEmpty()),
                modifier = Modifier.testTag("startTest"),
            ) { Text("开始自动测试") }
            OutlinedButton(onClick = { controller.stopTest() }, enabled = test.running, modifier = Modifier.testTag("stopTest")) { Text("停止") }
        }
        if (!link.simulated && connected) Hint("真实总线：测试会实际开锁和上电，请确保现场安全。")

        if (test.lines.isNotEmpty()) {
            SectionCard(if (test.running) "测试进行中…" else "测试过程") {
                SelectionContainer {
                    Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState(), reverseScrolling = true)) {
                        test.lines.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                    }
                }
            }
        }

        test.report?.let { r ->
            SectionCard("测试报告") {
                Text("总体结论：${r.verdict.label}${if (r.cancelled) "（已中止）" else ""}", color = verdictColor(r.verdict),
                    fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("verdict"))
                if (!r.physicalTransport) Hint("模拟器结果，不代表实物验收", WarnColor)
                if (r.possiblyEnergized.isNotEmpty()) Text("可能仍带电：${r.possiblyEnergized.joinToString()}", color = FailColor)
                r.boards.forEach { b ->
                    Text("${b.boardName}：${b.verdict.label}", color = verdictColor(b.verdict), fontWeight = FontWeight.Bold)
                    b.items.forEach { i -> Text("  ${i.id} ${i.name}：${i.verdict.label}", color = verdictColor(i.verdict)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    test.reportText?.let { f -> OutlinedButton(onClick = { shareFile(context, f) }) { Text("分享 TXT") } }
                    test.reportJson?.let { f -> OutlinedButton(onClick = { shareFile(context, f, "application/json") }) { Text("分享 JSON") } }
                    TextButton(onClick = { showFull = !showFull }) { Text(if (showFull) "收起全文" else "查看全文") }
                }
                test.reportText?.let { Hint("已保存：${it.absolutePath}") }
                if (showFull) SelectionContainer { Text(ReportFormatter.toText(r), fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
            }
        }

        EnduranceSection(controller, connected, busy, endurance, board.address)
    }

    preview?.let { lines ->
        val targets = controller.testTargets(allBoards)
        val needArm = plan.outputSingle || plan.outputMulti
        val outputBoards = targets.filter { cfg.profile(it).channelsOf(ChannelType.OUTPUT).isNotEmpty() }
        val unarmed = outputBoards.filter { it !in armed }
        var armNow by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text("即将执行的动作") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    lines.ifEmpty { listOf("（只执行只读测试项）") }.forEach { Text(it) }
                    if (needArm && unarmed.isNotEmpty()) {
                        LabeledCheckbox("我已核对地址 ${unarmed.joinToString(",")} 的接线档案：灯/继电器通道未接电控锁，允许持续上电", armNow) { armNow = it }
                        Hint("不勾选则跳过 T7/T8")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { preview = null; controller.startTest(targets, armNow) }, modifier = Modifier.testTag("confirmStart")) { Text("开始") }
            },
            dismissButton = { TextButton(onClick = { preview = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun EnduranceSection(
    controller: AppController,
    connected: Boolean,
    busy: Boolean,
    st: cn.tuodi.lockboard.app.EnduranceState,
    address: Int,
) {
    val cfg by controller.config.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var channels by remember(address) { mutableStateOf(cfg.profile(address).channelsOf(ChannelType.LOCK).toSet()) }
    SectionCard("寿命测试（地址 $address，循环 0x11 → 0x10 → 0x11）") {
        ChannelSelector(channels) { channels = it }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            IntField("轮数", cfg.enduranceCycles, 1..1_000_000, Modifier.weight(1f)) { v -> controller.updateConfig { it.copy(enduranceCycles = v) } }
            IntField("每轮间隔 ms", cfg.enduranceIntervalMs, 0..3_600_000, Modifier.weight(1f)) { v -> controller.updateConfig { it.copy(enduranceIntervalMs = v) } }
        }
        Hint("每次开锁前读取反馈：只有“关→开”才计为已验证动作；门未关回时计为未验证。")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { controller.startEndurance(channels.sorted(), cfg.enduranceCycles, cfg.enduranceIntervalMs) }, enabled = connected && !busy) { Text("开始寿命测试") }
            OutlinedButton(onClick = { controller.stopEndurance() }, enabled = st.running) { Text("停止") }
            st.file?.takeIf { it.exists() }?.let { f -> TextButton(onClick = { shareFile(context, f) }) { Text("分享") } }
        }
        st.status?.let { Text(it.summaryText(), fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.fillMaxWidth()) }
    }
}
