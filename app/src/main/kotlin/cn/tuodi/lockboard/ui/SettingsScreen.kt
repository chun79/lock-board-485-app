package cn.tuodi.lockboard.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.tuodi.lockboard.app.AppController
import cn.tuodi.lockboard.core.client.ClientSettings
import cn.tuodi.lockboard.core.config.AppInfo
import cn.tuodi.lockboard.core.config.BoardProfile
import cn.tuodi.lockboard.core.config.ChannelProfile
import cn.tuodi.lockboard.core.config.ChannelType
import cn.tuodi.lockboard.core.config.DipSwitch
import cn.tuodi.lockboard.core.sim.SimBoardConfig
import cn.tuodi.lockboard.core.sim.SimConfig

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(controller: AppController) {
    val cfg by controller.config.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var askReset by remember { mutableStateOf(false) }
    fun client(t: (ClientSettings) -> ClientSettings) = controller.updateConfig { it.copy(client = t(it.client).validated()) }
    fun sim(t: (SimConfig) -> SimConfig) = controller.updateConfig { it.copy(sim = t(it.sim)) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        SectionCard("通信时序（连接中修改立即生效）") {
            val c = cfg.client
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                IntField("查询超时 ms", c.readTimeoutMs, 20..10_000, Modifier.weight(1f)) { v -> client { it.copy(readTimeoutMs = v) } }
                IntField("读重试次数", c.readRetries, 0..5, Modifier.weight(1f)) { v -> client { it.copy(readRetries = v) } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                IntField("单路动作最长 ms", c.perLockPulseMaxMs, 50..10_000, Modifier.weight(1f)) { v -> client { it.copy(perLockPulseMaxMs = v) } }
                IntField("同时脉冲 ms", c.simultaneousPulseMs, 20..10_000, Modifier.weight(1f)) { v -> client { it.copy(simultaneousPulseMs = v) } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                IntField("动作余量 ms", c.actionMarginMs, 0..30_000, Modifier.weight(1f)) { v -> client { it.copy(actionMarginMs = v) } }
                IntField("帧间隔 ms", c.interFrameGapMs, 0..2_000, Modifier.weight(1f)) { v -> client { it.copy(interFrameGapMs = v) } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                IntField("迟到隔离 ms", c.lateReplyGuardMs, 0..10_000, Modifier.weight(1f)) { v -> client { it.copy(lateReplyGuardMs = v) } }
                IntField("迟到回复最长寿命 ms", c.lateReplyMaxAgeMs, 100..600_000, Modifier.weight(1f)) { v -> client { it.copy(lateReplyMaxAgeMs = v) } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                IntField("字节静默 ms", c.interByteTimeoutMs, 5..1_000, Modifier.weight(1f)) { v -> client { it.copy(interByteTimeoutMs = v) } }
                IntField("扫描超时 ms", cfg.scanTimeoutMs, 50..5_000, Modifier.weight(1f)) { v -> controller.updateConfig { it.copy(scanTimeoutMs = v) } }
            }
            IntField("轮询间隔 ms", cfg.pollIntervalMs, 200..60_000, Modifier.fillMaxWidth()) { v -> controller.updateConfig { it.copy(pollIntervalMs = v) } }
            LabeledSwitch("丢弃发送回显（适配器会回显发送数据时打开）", c.discardTxEcho) { v -> client { it.copy(discardTxEcho = v) } }
            Hint("脉冲动作（0x10/0x14/0x15、MODBUS 05/0F）按“路数 × 单路动作最长 + 余量”等待；文档：每路约 300 ms 通电 + 50 ms，失败最多再重试 3 次。" +
                "同一板上的新脉冲任务会替换正在执行的任务，所以 App 会等上一个动作窗口结束后才发下一个脉冲。")
            Hint("动作类指令永不自动重试；超时记为“结果未知”。回复不带请求标识：更早的同类请求未回复且未超过“最长寿命”时，新回复判为归属不明。")
        }

        SectionCard("上电输出安全") {
            IntField("电控锁允许的最长定时通电 s（0 = 禁止）", cfg.maxLockTimedSeconds, 0..60, Modifier.fillMaxWidth()) { v -> controller.updateConfig { it.copy(maxLockTimedSeconds = v) } }
            Hint("持续通电只允许用于已确认接线的灯/继电器通道。电控锁通道只能在此上限内定时通电，且读回确认关断前不能重新定时。", WarnColor)
        }

        ProfileEditor(controller)

        SectionCard("拨码地址换算") {
            var addr by remember { mutableIntStateOf(1) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                DipSwitchView(addr) { addr = it }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("地址 $addr", fontWeight = FontWeight.Bold)
                    Text("拨码(4321)：${DipSwitch.describe(addr)}")
                    if (addr == 0) Hint("全部 OFF（0）为无效地址", FailColor)
                }
            }
            Hint("点击开关切换。公式：地址 = S1×1 + S2×2 + S3×4 + S4×8（ON=1，向上），有效范围 1–15。")
        }

        SimulatorSettings(cfg.sim, ::sim)

        SectionCard("关于") {
            Text("App 版本：${controller.appVersion}")
            SelectionContainer {
                Column {
                    Text("开发：${AppInfo.AUTHOR} <${AppInfo.EMAIL}>")
                    Text("版权：${AppInfo.COPYRIGHT} · ${AppInfo.LICENSE}")
                    Text("项目主页：${AppInfo.REPO_URL}")
                    Hint("意见反馈：欢迎通过 GitHub Issues 或邮件提交问题与建议")
                }
            }
            Text("依据：12 路锁控板私有通讯协议、MODBUS RTU 协议（2026-10-06，固件 LOCK_12_MODBUS_TIMED_20261006）", modifier = Modifier.padding(top = 4.dp))
            Hint("私有协议：9600 8N1；DC 02 地址 指令 LEN 数据 SUM(地址..数据 累加低 8 位)，回复 DC FE。MODBUS RTU：同一串口，CRC16(A001)，地址 00 为广播。")
            Hint("首字节为 DC 的帧进入私有协议，其余进入 MODBUS；两套协议共用输出、定时器和脉冲任务。")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                OutlinedButton(onClick = { openUrlOrCopy(context, "项目主页", AppInfo.REPO_URL, "未检测到浏览器，已复制项目地址") }) {
                    Text("打开项目主页")
                }
                OutlinedButton(onClick = { openUrlOrCopy(context, "意见反馈", AppInfo.ISSUES_URL, "未检测到浏览器，已复制反馈地址") }) {
                    Text("意见反馈")
                }
                OutlinedButton(onClick = { copyText(context, "项目主页", AppInfo.REPO_URL, "已复制项目地址") }) {
                    Text("复制项目地址")
                }
            }
            OutlinedButton(onClick = { askReset = true }, modifier = Modifier.padding(top = 6.dp)) { Text("恢复全部默认设置") }
        }
    }
    if (askReset) ConfirmDialog("恢复默认", "将清除通信参数、板卡档案、测试与模拟器设置。", onConfirm = { controller.resetConfig() }, onDismiss = { askReset = false })
}

private fun copyText(context: Context, label: String, text: String, toastMessage: String) {
    val copied = runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return@runCatching false
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        true
    }.getOrDefault(false)
    Toast.makeText(context, if (copied) toastMessage else "无法复制，请手动记录：$text", Toast.LENGTH_SHORT).show()
}

private fun openUrlOrCopy(context: Context, label: String, url: String, fallbackToast: String) {
    val opened = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
    if (!opened) copyText(context, label, url, fallbackToast)
}

@Composable
private fun ProfileEditor(controller: AppController) {
    val cfg by controller.config.collectAsStateWithLifecycle()
    val board by controller.board.collectAsStateWithLifecycle()
    var address by remember(board.address) { mutableIntStateOf(board.address) }
    val p = cfg.profile(address)
    fun save(np: BoardProfile) = controller.updateConfig { it.withProfile(np) }

    SectionCard("板卡接线档案") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dropdown("地址", (1..15).toList(), address, { it.toString() }) { address = it }
            Spacer(Modifier.width(8.dp))
            DipSwitchView(address)
        }
        var name by remember(address, p.name) { mutableStateOf(p.name) }
        OutlinedTextField(name, { name = it; save(p.copy(name = it)) }, label = { Text("名称（如 A 柜上层）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Hint("持续输出（0x16/0x1A）只允许用于“灯/继电器”通道；修改已确认板的档案会撤销本次连接的确认。")
        for (ch in 1..12) {
            val c = p.channel(ch)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp).testTag("profile_$ch")) {
                Text("$ch", Modifier.width(24.dp), fontWeight = FontWeight.Bold)
                Dropdown("", ChannelType.entries.toList(), c.type, { it.label }) { t -> save(p.withChannel(ch, c.copy(type = t))) }
                Checkbox(checked = c.feedback, onCheckedChange = { f -> save(p.withChannel(ch, c.copy(feedback = f))) }, enabled = c.type == ChannelType.LOCK)
                Text("有反馈", Modifier.width(52.dp))
                var label by remember(address, ch, c.label) { mutableStateOf(c.label) }
                OutlinedTextField(label, { label = it; save(p.withChannel(ch, c.copy(label = it))) }, singleLine = true,
                    placeholder = { Text("标签") }, modifier = Modifier.weight(1f))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = { save(BoardProfile(address, p.name)) }) { Text("全部设为电控锁") }
            TextButton(onClick = { save(p.copy(channels = p.channels.map { it.copy(feedback = false) })) }) { Text("全部无反馈") }
        }
    }
}

private fun parseChannels(text: String): Set<Int> =
    text.split(',', '，', ' ').mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..12 }.toSet()

private fun parseMap(text: String): Map<Int, Int> =
    text.split(',', '，', ' ').mapNotNull { pair ->
        val parts = pair.split('>', '→')
        if (parts.size != 2) null else {
            val a = parts[0].trim().toIntOrNull(); val b = parts[1].trim().toIntOrNull()
            if (a in 1..12 && b in 1..12) a!! to b!! else null
        }
    }.toMap()

@Composable
private fun SimulatorSettings(s: SimConfig, update: ((SimConfig) -> SimConfig) -> Unit) {
    SectionCard("模拟器（下次连接时生效）") {
        var addrs by remember(s.boards) { mutableStateOf(s.boards.joinToString(",") { it.address.toString() }) }
        OutlinedTextField(addrs, { v ->
            addrs = v
            val list = v.split(',', '，', ' ').mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..15 }.distinct()
            if (list.isNotEmpty()) update { cur -> cur.copy(boards = list.map { a -> cur.boards.firstOrNull { it.address == a } ?: SimBoardConfig(a) }) }
        }, label = { Text("虚拟板地址（逗号分隔）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            IntField("每次通电 ms", s.pulseOnMs, 1..10_000, Modifier.weight(1f)) { v -> update { it.copy(pulseOnMs = v) } }
            IntField("断开后采样 ms", s.pulseSettleMs, 0..10_000, Modifier.weight(1f)) { v -> update { it.copy(pulseSettleMs = v) } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            IntField("失败重试次数", s.maxRetries, 0..10, Modifier.weight(1f)) { v -> update { it.copy(maxRetries = v) } }
            IntField("门自动关回 ms(0=手动)", s.autoCloseMs, 0..600_000, Modifier.weight(1f)) { v -> update { it.copy(autoCloseMs = v) } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            IntField("回复延迟 ms", s.responseDelayMs, 0..10_000, Modifier.weight(1f)) { v -> update { it.copy(responseDelayMs = v) } }
            IntField("丢包率 %", (s.dropRate * 100).toInt(), 0..100, Modifier.weight(1f)) { v -> update { it.copy(dropRate = v / 100.0) } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            IntField("坏校验率 %", (s.badChecksumRate * 100).toInt(), 0..100, Modifier.weight(1f)) { v -> update { it.copy(badChecksumRate = v / 100.0) } }
            Spacer(Modifier.weight(1f))
        }
        LabeledSwitch("适配器回显发送数据", s.echo) { v -> update { it.copy(echo = v) } }
        s.boards.forEach { b ->
            Text("虚拟板 ${b.address} 故障注入", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
            fun setBoard(nb: SimBoardConfig) = update { cur -> cur.copy(boards = cur.boards.map { if (it.address == b.address) nb else it }) }
            var dead by remember(b) { mutableStateOf(b.deadChannels.sorted().joinToString(",")) }
            var nofb by remember(b) { mutableStateOf(b.noFeedbackChannels.sorted().joinToString(",")) }
            var stuck by remember(b) { mutableStateOf(b.stuckOpenChannels.sorted().joinToString(",")) }
            var mis by remember(b) { mutableStateOf(b.miswired.entries.joinToString(",") { "${it.key}>${it.value}" }) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(dead, { dead = it; setBoard(b.copy(deadChannels = parseChannels(it))) }, label = { Text("驱动损坏") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(nofb, { nofb = it; setBoard(b.copy(noFeedbackChannels = parseChannels(it))) }, label = { Text("无反馈") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(stuck, { stuck = it; setBoard(b.copy(stuckOpenChannels = parseChannels(it))) }, label = { Text("反馈卡开") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(mis, { mis = it; setBoard(b.copy(miswired = parseMap(it))) }, label = { Text("错接 如 4>5") }, singleLine = true, modifier = Modifier.weight(1f))
            }
        }
    }
}
