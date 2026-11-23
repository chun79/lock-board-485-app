package cn.tuodi.lockboard.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.tuodi.lockboard.app.AppController
import cn.tuodi.lockboard.app.LinkStatus
import cn.tuodi.lockboard.core.client.LogKind
import cn.tuodi.lockboard.core.modbus.Crc16
import cn.tuodi.lockboard.core.modbus.Modbus
import cn.tuodi.lockboard.core.protocol.Cmd
import cn.tuodi.lockboard.core.protocol.FrameCodec
import cn.tuodi.lockboard.core.protocol.hex2
import cn.tuodi.lockboard.core.protocol.parseHex
import cn.tuodi.lockboard.core.protocol.toHex

private val KNOWN = Cmd.SUPPORTED + Cmd.UNSUPPORTED

private val MODBUS_FUNCTIONS = listOf(0x02, 0x03, 0x05, 0x06, 0x0F, 0x10, 0x01, 0x04)

@Composable
fun TerminalScreen(controller: AppController) {
    val link by controller.link.collectAsStateWithLifecycle()
    val entries by controller.logEntries.collectAsStateWithLifecycle()
    val lastResult by controller.lastResult.collectAsStateWithLifecycle()
    val board by controller.board.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val connected = link.status == LinkStatus.CONNECTED
    var address by rememberSaveable(board.address) { mutableIntStateOf(board.address) }
    var command by rememberSaveable { mutableIntStateOf(Cmd.GET_VERSION) }
    var dataHex by rememberSaveable { mutableStateOf("") }
    var rawHex by rememberSaveable { mutableStateOf("DC 02 01 01 00 02") }
    var listenMs by rememberSaveable { mutableIntStateOf(300) }
    var showNoise by rememberSaveable { mutableStateOf(true) }
    var follow by rememberSaveable { mutableStateOf(true) }
    var compact by rememberSaveable { mutableStateOf(false) }
    var mbAddress by rememberSaveable(board.address) { mutableIntStateOf(board.address) }
    var mbFunction by rememberSaveable { mutableIntStateOf(0x03) }
    var mbPayload by rememberSaveable { mutableStateOf("00 01 00 01") }
    var confirmBroadcast by remember { mutableStateOf<(() -> Unit)?>(null) }

    val frameError = remember(address, command, dataHex) { controller.frameError(address, command, dataHex) }
    val framePreview = remember(address, command, dataHex, frameError) {
        frameError ?: runCatching { FrameCodec.encode(address, command, parseHex(dataHex)).toHex() }.getOrElse { "数据段错误：${it.message}" }
    }
    val shown = remember(entries, showNoise) {
        if (showNoise) entries else entries.filter { it.kind !in setOf(LogKind.GARBAGE, LogKind.RX_ECHO, LogKind.RX_LATE) }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(shown.size, follow) { if (follow && shown.isNotEmpty()) listState.scrollToItem(shown.lastIndex) }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        TextButton(onClick = { compact = !compact }) { Text(if (compact) "展开发送区 ▾" else "收起发送区 ▴") }
        if (!compact) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                IntField("地址", address, 0..255, Modifier.weight(1f)) { address = it }
                Dropdown("指令", KNOWN, command, { "0x${it.hex2()} ${Cmd.describe(it)}" }) { command = it }
            }
            OutlinedTextField(dataHex, { dataHex = it }, label = { Text("数据段 HEX（如 01 或 00 07）") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("dataHex"))
            Text("帧：$framePreview", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            Button(onClick = { controller.sendFrame(address, command, dataHex) }, enabled = connected && frameError == null, modifier = Modifier.testTag("sendFrame")) {
                Text("发送私有帧（经安全策略）")
            }
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                IntField("MODBUS 地址", mbAddress, 0..247, Modifier.weight(1f)) { mbAddress = it }
                Dropdown("功能码", MODBUS_FUNCTIONS, mbFunction, { "%02X ".format(it) + Modbus.describeFunction(it) }) { mbFunction = it }
            }
            OutlinedTextField(mbPayload, { mbPayload = it }, label = { Text("功能码之后的数据 HEX（如 00 01 00 01）") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("mbPayload"))
            val mbPreview = remember(mbAddress, mbFunction, mbPayload) {
                runCatching { Crc16.append(listOf(mbAddress, mbFunction) + parseHex(mbPayload)).toHex() }.getOrElse { "数据错误：${it.message}" }
            }
            Text("帧：$mbPreview", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            Button(onClick = {
                val send = { controller.sendModbusFrame(mbAddress, mbFunction, mbPayload, broadcastConfirmed = mbAddress == 0) }
                if (mbAddress == 0) confirmBroadcast = send else send()
            }, enabled = connected && controller.frameError(mbAddress, mbFunction, mbPayload) == null, modifier = Modifier.testTag("sendModbus")) {
                Text("发送 MODBUS 帧（自动 CRC，经安全策略）")
            }
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            OutlinedTextField(rawHex, { rawHex = it }, label = { Text("原始 HEX") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("rawHex"))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                IntField("监听 ms", listenMs, 50..10_000, Modifier.weight(1f)) { listenMs = it }
                Button(onClick = {
                    if (controller.isBroadcastFrame(rawHex)) confirmBroadcast = { controller.sendRawHex(rawHex, listenMs, broadcastConfirmed = true) }
                    else controller.sendRawHex(rawHex, listenMs)
                }, enabled = connected, modifier = Modifier.testTag("sendRaw")) { Text("发送原始") }
            }
            Hint(if (link.simulated) "模拟器：允许任意字节。" else "真实总线：每次只允许 1 个完整帧（DC 02 私有帧或 CRC 正确的 MODBUS 请求），并检查安全策略。")
            if (lastResult.isNotBlank()) Text(lastResult, fontSize = 12.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            FilterChip(showNoise, { showNoise = !showNoise }, label = { Text("杂散/回显") })
            FilterChip(follow, { follow = !follow }, label = { Text("跟随") })
            OutlinedButton(onClick = { controller.clearLog() }) { Text("清空") }
            OutlinedButton(onClick = { shareFile(context, controller.exportLog()) }) { Text("导出") }
        }
        Hint(controller.statsSummary)
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth().testTag("log")) {
            items(shown, key = { it.seq }) { e ->
                Text(e.format(), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = logColor(e.kind))
            }
        }
    }
    confirmBroadcast?.let { action ->
        ConfirmDialog("MODBUS 广播", "地址 00 为广播：总线上所有板都会执行，且不回复。广播不允许上电输出；脉冲开锁会同时打开所有板的锁。确定发送？",
            onConfirm = action, onDismiss = { confirmBroadcast = null })
    }
}

private fun logColor(kind: LogKind): Color = when (kind) {
    LogKind.TX -> Color(0xFF1565C0)
    LogKind.RX -> Color(0xFF2E7D32)
    LogKind.RX_BAD, LogKind.ERROR -> FailColor
    LogKind.WARN, LogKind.RX_LATE -> WarnColor
    LogKind.GARBAGE, LogKind.RX_ECHO, LogKind.RX_STRAY -> UnknownColor
    LogKind.INFO -> Color(0xFF6A1B9A)
}
