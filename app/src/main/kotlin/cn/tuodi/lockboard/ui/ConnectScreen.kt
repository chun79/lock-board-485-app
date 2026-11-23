package cn.tuodi.lockboard.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.tuodi.lockboard.app.AppController
import cn.tuodi.lockboard.app.LinkStatus
import cn.tuodi.lockboard.app.PendingCarry
import cn.tuodi.lockboard.core.config.DipSwitch
import cn.tuodi.lockboard.core.config.Parity
import cn.tuodi.lockboard.core.config.TransportType
import cn.tuodi.lockboard.core.protocol.toHex
import cn.tuodi.lockboard.transport.UartTransport
import cn.tuodi.lockboard.transport.UsbAdapterInfo
import cn.tuodi.lockboard.transport.UsbAdapters

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConnectScreen(controller: AppController, usb: UsbAdapters?) {
    val cfg by controller.config.collectAsStateWithLifecycle()
    val link by controller.link.collectAsStateWithLifecycle()
    val scan by controller.scan.collectAsStateWithLifecycle()
    val board by controller.board.collectAsStateWithLifecycle()
    val outputs by controller.outputs.collectAsStateWithLifecycle()
    val pending by controller.pendingCarries.collectAsStateWithLifecycle()
    var askDiscard by remember { mutableStateOf<PendingCarry?>(null) }
    val connected = link.status == LinkStatus.CONNECTED
    val editable = link.status == LinkStatus.DISCONNECTED || link.status == LinkStatus.LOST
    var askDisconnect by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        SectionCard("通信链路") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TransportType.entries.forEach { t ->
                    FilterChip(
                        selected = cfg.connection.type == t,
                        enabled = editable,
                        onClick = { controller.updateConfig { it.copy(connection = it.connection.copy(type = t)) } },
                        label = { Text(t.label) },
                        modifier = Modifier.testTag("transport_${t.name}"),
                    )
                }
            }
            when (cfg.connection.type) {
                TransportType.USB -> UsbPanel(controller, usb, cfg.connection.usbDeviceName, editable)
                TransportType.UART -> UartPanel(controller, cfg.connection.uartPath, cfg.connection.uartRs485Ioctl, editable)
                TransportType.TCP -> Column {
                    OutlinedTextField(
                        value = cfg.connection.tcpHost, enabled = editable, singleLine = true,
                        onValueChange = { v -> controller.updateConfig { it.copy(connection = it.connection.copy(tcpHost = v.trim())) } },
                        label = { Text("串口服务器 IP") }, modifier = Modifier.fillMaxWidth(),
                    )
                    IntField("端口", cfg.connection.tcpPort, 1..65535, Modifier.fillMaxWidth()) { v ->
                        controller.updateConfig { it.copy(connection = it.connection.copy(tcpPort = v)) }
                    }
                    Hint("串口服务器须设为 TCP Server 透传模式，串口参数在服务器上设置为 9600 8N1。")
                }
                TransportType.SIMULATOR -> Hint(
                    "模拟器内置 ${cfg.sim.boards.size} 块虚拟板（地址 ${cfg.sim.boards.joinToString(",") { it.address.toString() }}），" +
                        "可在“配置”页设置回复格式和故障注入。模拟器结果不代表实物验收。",
                )
            }
            if (cfg.connection.type == TransportType.USB || cfg.connection.type == TransportType.UART) {
                SerialParamsRow(controller, editable)
            }
        }

        SectionCard("连接") {
            Text("状态：${link.status.label}", fontWeight = FontWeight.Bold,
                color = when (link.status) { LinkStatus.CONNECTED -> OpenColor; LinkStatus.LOST -> FailColor; else -> MaterialTheme.colorScheme.onSurface })
            if (link.description.isNotBlank()) Text(link.description)
            if (link.message.isNotBlank()) Hint(link.message)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = { controller.connect() }, enabled = editable, modifier = Modifier.testTag("connect")) { Text("连接") }
                OutlinedButton(
                    onClick = { if (outputs.isNotEmpty()) askDisconnect = true else controller.disconnect() },
                    enabled = connected || link.status == LinkStatus.CONNECTING,
                    modifier = Modifier.testTag("disconnect"),
                ) { Text("断开") }
            }
        }

        if (connected && pending.isNotEmpty()) SectionCard("其他 USB 适配器遗留的可能带电输出") {
            Hint("无法确认这些输出所在的总线是否就是当前总线。是同一条总线时带入并读回核对；否则请回到原总线处理或现场确认断电。", FailColor)
            pending.forEach { p ->
                Text("${p.label}：${p.outputs.joinToString()}", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { controller.adoptCarry(p.key) }, modifier = Modifier.testTag("adopt_${p.key}")) { Text("是同一条总线：带入并读回") }
                    OutlinedButton(onClick = { askDiscard = p }) { Text("现场已确认断电") }
                }
            }
        }

        SectionCard("总线扫描（地址 1–15，私有 0x01 + MODBUS 03 读 0001）") {
            Button(onClick = { controller.scan() }, enabled = connected && !scan.running, modifier = Modifier.testTag("scan")) {
                Text(if (scan.running) "扫描中… 地址 ${scan.current}" else "扫描总线")
            }
            if (scan.running) LinearProgressIndicator(progress = { scan.current / 15f }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            if (scan.entries.isNotEmpty()) {
                Text("在线 ${scan.online.size} 块：点击选择当前板", modifier = Modifier.padding(top = 8.dp))
                scan.entries.forEach { e ->
                    val online = e.online
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = online) { controller.selectBoard(e.address) }.padding(vertical = 4.dp)
                            .testTag("scan_${e.address}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = board.address == e.address, onClick = if (online) { { controller.selectBoard(e.address) } } else null, enabled = online)
                        Column(Modifier.weight(1f)) {
                            Text(
                                "地址 ${e.address}  " + if (online) {
                                    "在线  私有 ${e.privateVersion?.toHex() ?: "无回复"} · MODBUS ${e.modbusVersion?.let { "%04X".format(it) } ?: "无回复"}"
                                } else "无回复",
                                color = if (online) MaterialTheme.colorScheme.onSurface else UnknownColor,
                            )
                            Hint("拨码(4321)：${DipSwitch.describe(e.address)}" + (e.error?.takeIf { online }?.let { "  · $it" } ?: "") +
                                if (online && !e.bothProtocols) "  · 只有一种协议回复，与文档不符" else "")
                        }
                    }
                }
            }
        }
    }

    askDiscard?.let { p ->
        ConfirmDialog(
            title = "现场确认断电",
            text = "确认已在现场核实 ${p.label} 遗留的 ${p.outputs.joinToString()} 已断电？此操作只清除 App 的登记，不发送任何指令。",
            confirm = "已断电，清除登记",
            onConfirm = { controller.discardCarry(p.key) },
            onDismiss = { askDiscard = null },
        )
    }

    if (askDisconnect) {
        ConfirmDialog(
            title = "仍有输出可能带电",
            text = "本 App 开启的输出：${outputs.keys.sorted().joinToString()}。断开前将逐路发送 0x16 关断，并读回 0004 确认。",
            confirm = "关断后断开",
            onConfirm = { controller.disconnect() },
            onDismiss = { askDisconnect = false },
        )
    }
}

@Composable
private fun UsbPanel(controller: AppController, usb: UsbAdapters?, selectedName: String, editable: Boolean) {
    var adapters by remember { mutableStateOf(usb?.list() ?: emptyList<UsbAdapterInfo>()) }
    Column(Modifier.padding(top = 8.dp)) {
        if (usb?.hostSupported != true) Hint("此设备未声明 USB Host 支持", FailColor)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("USB 转 485 适配器（${adapters.size}）", Modifier.weight(1f))
            OutlinedButton(onClick = { adapters = usb?.list() ?: emptyList() }) { Text("刷新") }
        }
        if (adapters.isEmpty()) Hint("未发现支持的适配器（CH340/CP210x/FTDI/PL2303/CDC）。插入后点“刷新”。")
        adapters.forEach { a ->
            Row(
                Modifier.fillMaxWidth().clickable(enabled = editable) {
                    controller.updateConfig { it.copy(connection = it.connection.copy(usbDeviceName = a.deviceName)) }
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = a.deviceName == selectedName || (selectedName.isBlank() && a == adapters.first()), onClick = null)
                Column {
                    Text(a.label)
                    Hint("${a.deviceName} · ${if (a.hasPermission) "已授权" else "连接时请求授权"}")
                }
            }
        }
    }
}

@Composable
private fun UartPanel(controller: AppController, path: String, rs485: Boolean, editable: Boolean) {
    val candidates = remember { UartTransport.listCandidates() }
    Column(Modifier.padding(top = 8.dp)) {
        OutlinedTextField(
            value = path, enabled = editable, singleLine = true,
            onValueChange = { v -> controller.updateConfig { it.copy(connection = it.connection.copy(uartPath = v.trim())) } },
            label = { Text("设备节点") }, modifier = Modifier.fillMaxWidth(),
        )
        if (candidates.isEmpty()) Hint("未在 /dev 下发现串口节点")
        candidates.forEach { (p, ok) ->
            Row(Modifier.fillMaxWidth().clickable(enabled = editable) {
                controller.updateConfig { it.copy(connection = it.connection.copy(uartPath = p)) }
            }.padding(vertical = 2.dp)) {
                Text(p, Modifier.weight(1f))
                Text(if (ok) "可读写" else "无权限", color = if (ok) OpenColor else UnknownColor)
            }
        }
        LabeledSwitch("启用内核 RS485 模式（TIOCSRS485，RTS 控制收发）", rs485, enabled = editable) { v ->
            controller.updateConfig { it.copy(connection = it.connection.copy(uartRs485Ioctl = v)) }
        }
        Hint("仅用于收发器方向由 RTS 控制、且驱动支持该 ioctl 的主板；自动换向的 485 模块保持关闭。")
    }
}

@Composable
private fun SerialParamsRow(controller: AppController, editable: Boolean) {
    val cfg by controller.config.collectAsStateWithLifecycle()
    val s = cfg.serial
    Column(Modifier.padding(top = 8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Dropdown("波特率", listOf(1200, 2400, 4800, 9600, 19200, 38400, 57600, 115200), s.baud, { it.toString() }, enabled = editable) { v ->
                controller.updateConfig { it.copy(serial = it.serial.copy(baud = v)) }
            }
            Dropdown("数据位", listOf(8, 7), s.dataBits, { it.toString() }, enabled = editable) { v ->
                controller.updateConfig { it.copy(serial = it.serial.copy(dataBits = v)) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Dropdown("校验", Parity.entries.toList(), s.parity, { it.label }, enabled = editable) { v ->
                controller.updateConfig { it.copy(serial = it.serial.copy(parity = v)) }
            }
            Dropdown("停止位", listOf(1, 2), s.stopBits, { it.toString() }, enabled = editable) { v ->
                controller.updateConfig { it.copy(serial = it.serial.copy(stopBits = v)) }
            }
        }
        if (!s.isProtocolDefault) Hint("协议规定 9600 8N1，当前为 ${s.describe()}", WarnColor)
    }
}
