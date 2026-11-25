package cn.tuodi.lockboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.tuodi.lockboard.app.AppController
import cn.tuodi.lockboard.app.ControlProtocol
import cn.tuodi.lockboard.app.LinkStatus
import cn.tuodi.lockboard.core.config.ChannelType
import cn.tuodi.lockboard.core.config.DipSwitch
import cn.tuodi.lockboard.core.protocol.LockMask
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class Pending(val title: String, val text: String, val action: () -> Unit)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ControlScreen(controller: AppController) {
    val cfg by controller.config.collectAsStateWithLifecycle()
    val link by controller.link.collectAsStateWithLifecycle()
    val board by controller.board.collectAsStateWithLifecycle()
    val protocol by controller.protocol.collectAsStateWithLifecycle()
    val polling by controller.polling.collectAsStateWithLifecycle()
    val armed by controller.armed.collectAsStateWithLifecycle()
    val outputs by controller.outputs.collectAsStateWithLifecycle()
    val lastResult by controller.lastResult.collectAsStateWithLifecycle()
    val busy by controller.busy.collectAsStateWithLifecycle()
    val sim by controller.simStates.collectAsStateWithLifecycle()
    val test by controller.test.collectAsStateWithLifecycle()
    val endurance by controller.endurance.collectAsStateWithLifecycle()
    val profile = cfg.profile(board.address)
    val linkOpen = link.status.isOpen
    val locked = test.running || endurance.running
    val enabled = linkOpen && !locked
    val outputChannels = profile.channelsOf(ChannelType.OUTPUT)
    val isArmed = board.address in armed
    var selection by remember { mutableStateOf(emptySet<Int>()) }
    var pending by remember { mutableStateOf<Pending?>(null) }
    var timedSeconds by remember { mutableIntStateOf(5) }
    LaunchedEffect(board.address) { pending = null }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        SectionCard("当前板") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dropdown("地址", (1..15).toList(), board.address, { it.toString() }, enabled = !locked) { controller.selectBoard(it) }
                Spacer(Modifier.width(12.dp))
                DipSwitchView(board.address)
            }
            Text(profile.displayName(), fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
            Text("版本：${controller.versionText(board)}（文档基准：私有 ≥ 02 00，MODBUS ≥ 0002）")
            Hint("拨码(4321)：${DipSwitch.describe(board.address)}" + (board.updatedAtMillis?.let { "  · 更新于 ${SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date(it))}" } ?: ""))
            board.lastError?.let { Hint("最近错误：$it", FailColor) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                Text("控制协议：")
                ControlProtocol.entries.forEach { p ->
                    FilterChip(selected = protocol == p, onClick = { controller.setProtocol(p) }, label = { Text(p.label) }, modifier = Modifier.testTag("proto_${p.name}"))
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { controller.readVersion(board.address) }, enabled = enabled) { Text("读版本") }
                OutlinedButton(onClick = { controller.readAll(board.address) }, enabled = enabled, modifier = Modifier.testTag("readAll")) {
                    Text(if (protocol == ControlProtocol.MODBUS) "读反馈 02" else "读反馈 0x12")
                }
            }
            LabeledSwitch("自动轮询反馈（每 ${cfg.pollIntervalMs} ms）", polling, enabled = enabled) { controller.togglePolling(it) }
            if (locked) Hint("自动测试或寿命测试进行中，手动操作已禁用", WarnColor)
            when {
                !linkOpen -> Hint("未连接：请先在“连接”页连接总线", WarnColor)
                link.status == LinkStatus.PROBING -> Hint("通道已打开，正在探测在线板卡…")
                link.status == LinkStatus.NO_BOARD -> Hint("通道已打开，但尚未检测到在线板卡；请检查接线、供电与串口参数，收到板卡回复后将转为“已连接”", WarnColor)
            }
        }

        if (lastResult.isNotBlank() || busy != null) {
            Text(busy?.let { "执行中：$it" } ?: lastResult, modifier = Modifier.padding(4.dp).testTag("lastResult"))
        }

        SectionCard("持续 / 定时输出（读回 MODBUS 0004）") {
            val rb = board.outputs
            Text("0004 读回：" + (rb?.let { "[${LockMask.describe(it)}] 通电" } ?: "未读取"), modifier = Modifier.testTag("outputsReadback"))
            Hint("0004 是持续输出控制字，不代表物理电平；实际输出 = 持续输出 OR 脉冲输出。")
            if (outputs.isNotEmpty()) {
                Text("本 App 开启、尚未经读回确认关断：", color = WarnColor, modifier = Modifier.padding(top = 4.dp))
                outputs.entries.sortedBy { it.key }.forEach { (k, s) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("$k：${s.label}", color = WarnColor, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            val gen = controller.outputGeneration(k)
                            if (gen != null) pending = Pending("现场确认断电", "确认已在现场核实 $k 的负载已断电？此操作只清除 App 的登记，不发送任何指令。") {
                                controller.confirmOffManually(k, gen)
                            }
                        }) { Text("现场已确认断电") }
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { controller.readOutputs(board.address) }, enabled = enabled, modifier = Modifier.testTag("readOutputs")) { Text("读回 0004") }
                Button(onClick = { controller.cleanupOutputs() }, enabled = linkOpen && outputs.isNotEmpty(), modifier = Modifier.testTag("cleanup")) { Text("逐路关断并读回确认") }
            }
            if (outputChannels.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IntField("定时秒数", timedSeconds, 1..65535, Modifier.width(140.dp)) { timedSeconds = it }
                    Spacer(Modifier.width(8.dp))
                    Hint("通道卡片上的“定时”按此秒数开启，到期由板卡自动撤销")
                }
            }
        }

        if (outputChannels.isNotEmpty() || cfg.maxLockTimedSeconds > 0) {
            SectionCard("接线档案确认（上电输出安全门）") {
                Text("灯/继电器通道：${outputChannels.joinToString(",").ifEmpty { "无" }}；其余为电控锁或未使用。")
                if (cfg.maxLockTimedSeconds > 0) Hint("设置中允许电控锁定时通电最长 ${cfg.maxLockTimedSeconds} s（同一路未读回关断前不能重新定时）", WarnColor)
                Text(if (isArmed) "本次连接已确认，允许上电输出" else "尚未确认：上电输出指令将被拒绝", color = if (isArmed) OpenColor else WarnColor)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val addr = board.address
                        pending = Pending("确认接线档案",
                            "请到现场核对：地址 $addr 的第 ${outputChannels.joinToString(",")} 路接的是灯/继电器等可长时间通电的负载，" +
                                "没有接电控锁。持续通电会烧坏电控锁。") { controller.arm(addr) }
                    }, enabled = linkOpen && !isArmed, modifier = Modifier.testTag("arm")) { Text("确认接线") }
                    OutlinedButton(onClick = { controller.disarm(board.address) }, enabled = linkOpen && isArmed) { Text("撤销") }
                }
            }
        }

        SectionCard("12 路通道") {
            BoxWithConstraints {
                val columns = (maxWidth / 165.dp).toInt().coerceIn(1, 4)
                Column {
                    (1..12).chunked(columns).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 3.dp)) {
                            row.forEach { ch ->
                                ChannelCard(
                                    controller, ch, profile.channel(ch).type, profile.channel(ch).label,
                                    controller.channelStateText(ch), enabled, timedSeconds,
                                    readbackOn = board.outputs?.let { LockMask.isSet(it, ch) },
                                    simOutput = sim.firstOrNull { it.address == board.address }?.let { s -> s.outputOn.getOrNull(ch - 1) to s.timerRemainingMs.getOrNull(ch - 1) },
                                    simulated = link.simulated, address = board.address,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }

        SectionCard("批量操作（${protocol.label}）") {
            Text("选择通道：")
            ChannelSelector(selection) { selection = it }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { selection = profile.channelsOf(ChannelType.LOCK).toSet() }) { Text("选全部锁") }
                TextButton(onClick = { selection = outputChannels.toSet() }) { Text("选全部输出") }
                TextButton(onClick = { selection = emptySet() }) { Text("清空") }
            }
            val sel = selection.sorted()
            val modbus = protocol == ControlProtocol.MODBUS
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { controller.unlockMany(sel, board.address) }, enabled = enabled && sel.isNotEmpty(), modifier = Modifier.testTag("unlockMany")) {
                    Text(if (modbus) "逐路开 0F" else "逐路开 0x15")
                }
                Button(onClick = {
                    val addr = board.address
                    pending = Pending("同时开多个锁", "地址 $addr 第 ${sel.joinToString(",")} 路将同时通电约 300 ms，瞬时电流叠加，请确认电源容量。") {
                        controller.unlockSimultaneous(sel, addr)
                    }
                }, enabled = enabled && sel.isNotEmpty()) { Text(if (modbus) "同时开 0006" else "同时开 0x18") }
                Button(onClick = { controller.setOutputs(sel, board.address) }, enabled = enabled && sel.isNotEmpty() && isArmed) {
                    Text(if (modbus) "整体输出 0004" else "整体输出 0x1A")
                }
                OutlinedButton(onClick = { controller.setOutputs(emptyList(), board.address) }, enabled = enabled) { Text("输出全关") }
                Button(onClick = {
                    val addr = board.address
                    pending = Pending("开全部锁",
                        "地址 $addr 全部 12 路逐路脉冲（含灯/继电器 ${outputChannels.joinToString(",").ifEmpty { "无" }} 与未使用通道），每路最长约 1.4 s。") {
                        controller.unlockAll(addr)
                    }
                }, enabled = enabled, modifier = Modifier.testTag("unlockAll")) { Text(if (modbus) "开全部 0F" else "开全部 0x14") }
            }
            Hint("整体输出写入覆盖全部 12 路：未选中的通道会被关闭，并取消所有单路定时（文档 4.9 / MODBUS 第 6 章）。只有已确认接线的灯/继电器通道能被开启。")
        }
    }

    pending?.let { p -> ConfirmDialog(p.title, p.text, onConfirm = p.action, onDismiss = { pending = null }) }
}

@Composable
private fun ChannelCard(
    controller: AppController,
    ch: Int,
    type: ChannelType,
    label: String,
    state: String,
    enabled: Boolean,
    timedSeconds: Int,
    readbackOn: Boolean?,
    simOutput: Pair<Boolean?, Long?>?,
    simulated: Boolean,
    address: Int,
    modifier: Modifier,
) {
    val color = when (state) { "打开" -> OpenColor; "关闭" -> ClosedColor; else -> UnknownColor }
    Card(modifier.testTag("channel_$ch"), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).background(color, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text("第 $ch 路", fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(state, color = color, modifier = Modifier.testTag("state_$ch"))
            }
            Hint(type.label + if (label.isNotBlank()) " · $label" else "")
            if (readbackOn == true) Hint("● 0004 读回：持续输出开", WarnColor)
            simOutput?.let { (on, timer) -> if (on == true) Hint("模拟负载通电" + (timer?.let { "，剩 ${it / 1000.0} s" } ?: ""), WarnColor) }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { controller.unlock(ch, address) }, enabled = enabled, modifier = Modifier.testTag("unlock_$ch")) { Text("开锁") }
                TextButton(onClick = { controller.readOne(ch, address) }, enabled = enabled) { Text("读") }
            }
            if (type == ChannelType.OUTPUT) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    TextButton(onClick = { controller.setOutput(ch, true, address) }, enabled = enabled, modifier = Modifier.testTag("outOn_$ch")) { Text("开") }
                    TextButton(onClick = { controller.setOutput(ch, false, address) }, enabled = enabled, modifier = Modifier.testTag("outOff_$ch")) { Text("关") }
                    TextButton(onClick = { controller.setOutputTimed(ch, timedSeconds, address) }, enabled = enabled, modifier = Modifier.testTag("outTimed_$ch")) { Text("定时") }
                }
            } else if (type == ChannelType.LOCK) {
                TextButton(onClick = { controller.setOutputTimed(ch, timedSeconds, address) }, enabled = enabled) { Text("定时通电 $timedSeconds s", color = Color.Gray) }
            }
            if (simulated && state == "打开") {
                TextButton(onClick = { controller.simCloseDoor(address, ch) }) { Text("模拟关门", color = Color.Gray) }
            }
        }
    }
}
