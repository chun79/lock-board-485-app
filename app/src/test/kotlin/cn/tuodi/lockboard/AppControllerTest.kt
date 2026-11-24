package cn.tuodi.lockboard

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.tuodi.lockboard.app.AndroidTransportFactory
import cn.tuodi.lockboard.app.AppController
import cn.tuodi.lockboard.app.ConfigStore
import cn.tuodi.lockboard.app.ControlProtocol
import cn.tuodi.lockboard.app.LinkStatus
import cn.tuodi.lockboard.app.OpenedTransport
import cn.tuodi.lockboard.app.TransportFactory
import cn.tuodi.lockboard.core.client.ByteTransport
import cn.tuodi.lockboard.core.client.LogKind
import cn.tuodi.lockboard.core.client.OutputKey
import cn.tuodi.lockboard.core.client.TransportKind
import cn.tuodi.lockboard.core.config.ChannelProfile
import cn.tuodi.lockboard.core.config.ChannelType
import cn.tuodi.lockboard.core.config.ConfigJson
import cn.tuodi.lockboard.core.modbus.Crc16
import cn.tuodi.lockboard.core.protocol.LockMask
import cn.tuodi.lockboard.core.sim.SimBoardConfig
import cn.tuodi.lockboard.core.sim.SimConfig
import cn.tuodi.lockboard.core.sim.SimulatedBus
import cn.tuodi.lockboard.core.testing.Answer
import cn.tuodi.lockboard.core.testing.Verdict
import cn.tuodi.lockboard.transport.UsbAdapters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** Controller-level integration on Android 11 (API 30) with the simulator; real time, no UI. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class AppControllerTest {
    private lateinit var scope: CoroutineScope
    private lateinit var controller: AppController
    private lateinit var store: ConfigStore
    private lateinit var exportDir: File

    private fun waitFor(timeoutMs: Long = 15_000, what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError("超时等待：$what")
            Thread.sleep(10)
        }
    }

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        ctx.getSharedPreferences("lockboard", 0).edit().clear().commit()
        store = ConfigStore(ctx)
        store.update {
            it.copy(
                sim = SimConfig(boards = listOf(SimBoardConfig(1), SimBoardConfig(4)), pulseOnMs = 10, pulseSettleMs = 5, autoCloseMs = 400, responseDelayMs = 1),
                client = it.client.copy(readTimeoutMs = 80, perLockPulseMaxMs = 60, simultaneousPulseMs = 20, actionMarginMs = 60,
                    interFrameGapMs = 1, lateReplyGuardMs = 60, lateReplyMaxAgeMs = 300),
                plan = it.plan.copy(communicationCount = 3, stressCount = 6, settleMs = 20, unlockIntervalMs = 5, waitClosedMs = 1000,
                    timedSeconds = 1, timerToleranceMs = 400),
                scanTimeoutMs = 60,
            )
        }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        exportDir = File(ctx.filesDir, "exports").apply { mkdirs() }
        controller = AppController(store, AndroidTransportFactory(UsbAdapters(ctx)), scope, exportDir, "test")
    }

    @After fun tearDown() { scope.cancel() }

    private fun connectAndScan() {
        controller.connect()
        waitFor(what = "连接") { controller.link.value.status == LinkStatus.CONNECTED }
        controller.scan()
        waitFor(what = "扫描") { controller.scan.value.finishedAtMillis != null }
    }

    private fun withLamp() = store.update { it.withProfile(it.profile(1).withChannel(12, ChannelProfile(ChannelType.OUTPUT, "灯"))) }

    @Test fun `scan finds boards on both protocols`() {
        connectAndScan()
        val online = controller.scan.value.online
        assertEquals(listOf(1, 4), online.map { it.address })
        assertTrue(online.all { it.bothProtocols && it.privateVersion == listOf(2, 0) && it.modbusVersion == 2 })
        assertEquals(15, controller.scan.value.entries.size)
    }

    @Test fun `unlock with either protocol updates the board view`() {
        connectAndScan()
        controller.selectBoard(4)
        controller.unlock(7)
        waitFor(what = "私有开锁") { controller.board.value.mask?.let { LockMask.isSet(it, 7) } == true }
        controller.setProtocol(ControlProtocol.MODBUS)
        controller.unlock(9)
        waitFor(what = "MODBUS 开锁") { controller.board.value.mask?.let { LockMask.isSet(it, 9) } == true }
        assertTrue(controller.lastResult.value.contains("05"))
        assertEquals("打开", controller.channelStateText(9))
    }

    @Test fun `outputs need arming, are read back, and are switched off on disconnect`() {
        withLamp()
        connectAndScan()
        controller.selectBoard(1)
        controller.setOutput(12, true)
        waitFor(what = "未确认拒绝") { controller.lastResult.value.contains("安全策略拒绝") }
        controller.arm(1)
        controller.setOutput(12, true)
        waitFor(what = "输出开并读回") { controller.board.value.outputs == LockMask.bit(12) }
        assertTrue(controller.outputs.value.containsKey(OutputKey(1, 12)))
        // Editing the profile revokes the confirmation.
        store.update { it.withProfile(it.profile(1).copy(name = "改名")) }
        waitFor(what = "撤销确认") { 1 !in controller.armed.value }
        controller.disconnect()
        waitFor(what = "断开") { controller.link.value.status == LinkStatus.DISCONNECTED }
        assertTrue(controller.log.snapshot().any { it.kind == LogKind.TX && it.text.contains("持续输出 #12 关") })
        assertTrue(controller.log.snapshot().any { it.kind == LogKind.TX && it.text.contains("03 读寄存器 0004") })
        assertTrue(controller.outputs.value.isEmpty())
    }

    @Test fun `timed output via MODBUS expires and is cleared by read-back`() {
        withLamp()
        connectAndScan()
        controller.selectBoard(1)
        controller.arm(1)
        controller.setProtocol(ControlProtocol.MODBUS)
        controller.setOutputTimed(12, 1)
        waitFor(what = "定时开") { controller.board.value.outputs == LockMask.bit(12) }
        Thread.sleep(1300)
        controller.readOutputs()
        waitFor(what = "到期后读回为 0") { controller.board.value.outputs == 0 && controller.outputs.value.isEmpty() }
    }

    @Test fun `automatic test over all scanned boards produces saved reports`() {
        connectAndScan()
        controller.startTest(controller.testTargets(allScanned = true), armOutputs = false)
        waitFor(120_000, "自动测试") { controller.test.value.report != null && !controller.test.value.running }
        val st = controller.test.value
        assertEquals(listOf(1, 4), st.report!!.boards.map { it.address })
        assertEquals(Verdict.PASS, st.report!!.boards.first().items.first { it.id == "T0" }.verdict)
        assertFalse(st.report!!.verdict == Verdict.FAIL)
        assertTrue(st.reportText!!.readText().contains("测试报告"))
        assertTrue(org.json.JSONObject(st.reportJson!!.readText()).getBoolean("simulated"))
    }

    @Test fun `manual confirmation prompts are answered through the controller`() {
        store.update { it.copy(plan = it.plan.copy(manualConfirm = true, communication = false, consistency = false, robustness = false,
            modbusExceptions = false, stress = false, modbusUnlockEach = false, unlockMany = false, modbusUnlockMany = false,
            unlockSimultaneous = false, modbusSimultaneous = false, unlockAll = false)) }
        connectAndScan()
        controller.selectBoard(1)
        controller.startTest(listOf(1), armOutputs = false)
        var answered = 0
        while (controller.test.value.report == null) {
            val p = controller.prompt.value
            if (p != null) { controller.answerPrompt(Answer.YES); answered++; waitFor(what = "下一个提示") { controller.prompt.value?.id != p.id } }
            Thread.sleep(5)
        }
        assertEquals(12, answered)
        assertEquals(Verdict.PASS, controller.test.value.report!!.verdict)
    }

    @Test fun `terminal refuses unsafe frames and sends both protocols`() {
        connectAndScan()
        controller.sendRawHex("dc 02 01 16 02 01 01 1b", 200)
        waitFor(what = "拒绝私有原始帧") { controller.lastResult.value.startsWith("已拒绝") }
        controller.sendModbusFrame(1, 0x06, "00 05 00 00", broadcastConfirmed = false)
        waitFor(what = "拒绝写 0005") { controller.lastResult.value.contains("0005") }
        controller.sendFrame(1, 0x12, "")
        waitFor(what = "私有构造帧") { controller.lastResult.value.contains("自定义 0x12") }
        controller.sendModbusFrame(1, 0x03, "00 01 00 01", broadcastConfirmed = false)
        waitFor(what = "MODBUS 构造帧") { controller.lastResult.value.contains("0001=0002") }
        assertTrue(controller.exportLog().readText().contains("发送"))
    }

    @Test fun `endurance test runs and writes its file`() {
        connectAndScan()
        controller.selectBoard(1)
        controller.startEndurance(listOf(2, 3), cycles = 2, intervalMs = 500)
        waitFor(20_000, "寿命测试") { controller.endurance.value.status?.finished == true && !controller.endurance.value.running }
        assertEquals(listOf(2, 2), controller.endurance.value.status!!.channels.map { it.verifiedActions })
        assertTrue(controller.endurance.value.file!!.readText().contains("寿命测试"))
    }

    @Test fun `disconnect while connecting closes the transport opened afterwards`() {
        var opened: SimulatedBus? = null
        val slow = TransportFactory { cfg ->
            val bus = SimulatedBus(cfg.sim).also { opened = it }
            kotlinx.coroutines.delay(400)
            OpenedTransport(bus)
        }
        val c2 = AppController(store, slow, scope, exportDir, "test")
        c2.connect()
        waitFor(what = "资源已创建") { opened != null }
        c2.disconnect()
        assertEquals(LinkStatus.DISCONNECTED, c2.link.value.status)
        Thread.sleep(700)
        assertEquals(LinkStatus.DISCONNECTED, c2.link.value.status)
        assertNotNull(opened)
        assertTrue("过期连接打开的传输必须被关闭", runCatching { opened!!.read(ByteArray(8), 1) }.isFailure)
    }

    @Test fun `outputs left by a lost link are carried into the next session and read back`() {
        withLamp()
        connectAndScan()
        controller.selectBoard(1)
        controller.arm(1)
        controller.setOutput(12, true)
        waitFor(what = "输出开") { controller.board.value.outputs == LockMask.bit(12) }
        controller.onUsbDetached("模拟总线") // link lost: no clean-up possible
        waitFor(what = "链路断开") { controller.link.value.status == LinkStatus.LOST }
        controller.connect()
        waitFor(what = "重连") { controller.link.value.status == LinkStatus.CONNECTED }
        waitFor(what = "带入上次会话的输出") { controller.log.snapshot().any { it.text.contains("上次会话未经读回确认关断的输出") } }
        // The new simulated bus reads 0004 = 0, which clears the carried registration.
        waitFor(what = "读回清账") { controller.outputs.value.isEmpty() }
    }

    @Test fun `carried outputs belong to their bus and are not cleared by another bus`() {
        withLamp()
        connectAndScan()
        controller.selectBoard(1)
        controller.arm(1)
        controller.setOutput(12, true)
        waitFor(what = "输出开") { controller.board.value.outputs == LockMask.bit(12) }
        controller.onUsbDetached("模拟总线")
        waitFor(what = "链路断开") { controller.link.value.status == LinkStatus.LOST }
        val carriedMsg = "上次会话未经读回确认关断的输出"
        // Another bus (a silent TCP server): physical link opens in NO_BOARD, never CONNECTED, and does not touch simulator carry.
        java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { server ->
            controller.updateConfig { it.copy(connection = it.connection.copy(type = cn.tuodi.lockboard.core.config.TransportType.TCP, tcpHost = "127.0.0.1", tcpPort = server.localPort)) }
            controller.connect()
            waitFor(what = "TCP 无板卡进入 NO_BOARD") { controller.link.value.status == LinkStatus.NO_BOARD }
            assertFalse(controller.link.value.status == LinkStatus.CONNECTED)
            Thread.sleep(200)
            assertFalse(controller.log.snapshot().any { it.text.contains(carriedMsg) })
            assertTrue(controller.outputs.value.isEmpty())
            controller.disconnect()
            waitFor(what = "TCP 断开") { controller.link.value.status == LinkStatus.DISCONNECTED }
        }
        controller.updateConfig { it.copy(connection = it.connection.copy(type = cn.tuodi.lockboard.core.config.TransportType.SIMULATOR)) }
        controller.connect()
        waitFor(what = "重连模拟器") { controller.link.value.status == LinkStatus.CONNECTED }
        waitFor(what = "带入上次会话的输出") { controller.log.snapshot().any { it.text.contains(carriedMsg) } }
    }

    @Test fun `outputs of a USB adapter without serial number are only imported on operator confirmation`() {
        withLamp()
        store.update { it.copy(connection = it.connection.copy(type = cn.tuodi.lockboard.core.config.TransportType.USB)) }
        // Each "USB adapter" is a fresh simulated bus without a serial number.
        var bus: SimulatedBus? = null
        val usbLike = TransportFactory { cfg -> OpenedTransport(SimulatedBus(cfg.sim).also { bus = it }) }
        val c2 = AppController(store, usbLike, scope, exportDir, "test")
        c2.connect()
        waitFor(what = "连接") { c2.link.value.status == LinkStatus.CONNECTED }
        c2.scan()
        waitFor(what = "扫描") { !c2.scan.value.running && c2.scan.value.online.isNotEmpty() }
        c2.selectBoard(1)
        c2.arm(1)
        c2.setOutput(12, true)
        waitFor(what = "输出开") { c2.outputs.value.isNotEmpty() }
        c2.onUsbDetached("模拟总线")
        waitFor(what = "链路断开") { c2.link.value.status == LinkStatus.LOST }
        c2.connect() // another adapter (reads 0004 = 0): must not clear the registration by itself
        waitFor(what = "重连") { c2.link.value.status == LinkStatus.CONNECTED }
        waitFor(what = "遗留登记提示") { c2.pendingCarries.value.singleOrNull()?.outputs == listOf(OutputKey(1, 12)) }
        assertTrue(c2.outputs.value.isEmpty())
        val reads = { bus!!.receivedModbusFrames.count { it[1] == 0x03 && it[3] == 0x04 } }
        val before = reads()
        c2.adoptCarry(c2.pendingCarries.value.single().key)
        assertTrue(c2.pendingCarries.value.isEmpty())
        waitFor(what = "认领后读回 0004") { reads() > before }
        waitFor(what = "读回清账") { c2.lastResult.value.contains("登记均已清除") && c2.outputs.value.isEmpty() }
    }

    @Test fun `scan results do not survive a reconnect`() {
        connectAndScan()
        assertTrue(controller.scan.value.online.isNotEmpty())
        controller.disconnect()
        waitFor(what = "断开") { controller.link.value.status == LinkStatus.DISCONNECTED }
        controller.connect()
        waitFor(what = "重连") { controller.link.value.status == LinkStatus.CONNECTED }
        assertTrue(controller.scan.value.entries.isEmpty())
        assertTrue(controller.testTargets(allScanned = true).isEmpty())
    }

    @Test fun `configuration persists across store instances`() {
        store.update { it.withProfile(it.profile(3).copy(name = "三号柜")).copy(maxLockTimedSeconds = 2) }
        val again = ConfigStore(ApplicationProvider.getApplicationContext())
        assertEquals("三号柜", again.config.value.profile(3).name)
        assertEquals(2, again.config.value.maxLockTimedSeconds)
        assertEquals(ConfigJson.encode(store.config.value), ConfigJson.encode(again.config.value))
    }

    private class PhysicalSimTransport(
        override val kind: TransportKind = TransportKind.UART,
        override val description: String = "/dev/ttyS1",
        @Volatile var target: ByteTransport? = null,
    ) : ByteTransport {
        @Volatile var closed = false
        override fun write(bytes: ByteArray) {
            if (closed) throw java.io.IOException("串口已关闭")
            target?.write(bytes)
        }
        override fun read(buffer: ByteArray, timeoutMs: Int): Int {
            if (closed) throw java.io.IOException("串口已关闭")
            val t = target
            return if (t != null) t.read(buffer, timeoutMs) else {
                Thread.sleep(timeoutMs.coerceIn(1, 50).toLong())
                0
            }
        }
        override fun drainInput(): ByteArray = target?.drainInput() ?: ByteArray(0)
        override fun close() {
            closed = true
            target?.close()
        }
    }

    @Test fun `silent physical UART opens in NO_BOARD, never CONNECTED, and allows terminal and disconnect`() {
        val transport = PhysicalSimTransport()
        val c2 = AppController(store, TransportFactory { OpenedTransport(transport, "RS485 已启用") }, scope, exportDir, "test")
        val seen = CopyOnWriteArrayList<LinkStatus>()
        val watch = scope.launch { c2.link.collect { seen += it.status } }
        try {
            c2.connect()
            waitFor(what = "静默串口进入 NO_BOARD") { c2.link.value.status == LinkStatus.NO_BOARD }
            assertTrue(c2.link.value.status.isOpen)
            assertFalse(LinkStatus.CONNECTED in seen)
            assertTrue(c2.link.value.message.contains("无回复"))
            assertTrue(c2.link.value.message.contains("RS485 已启用"))
            c2.sendFrame(1, 0x01, "")
            waitFor(what = "终端发送超时") { c2.lastResult.value.contains("超时") }
            assertEquals(LinkStatus.NO_BOARD, c2.link.value.status)
            assertFalse(LinkStatus.CONNECTED in seen)
            c2.disconnect()
            waitFor(what = "断开") { c2.link.value.status == LinkStatus.DISCONNECTED }
            waitFor(what = "传输关闭") { transport.closed }
        } finally {
            watch.cancel()
        }
    }

    @Test fun `physical UART with online board probes address 4 and transitions to CONNECTED`() {
        val bus = SimulatedBus(SimConfig(boards = listOf(SimBoardConfig(4)), responseDelayMs = 1))
        val transport = PhysicalSimTransport(target = bus)
        val c2 = AppController(store, TransportFactory { OpenedTransport(transport) }, scope, exportDir, "test")
        c2.connect()
        waitFor(what = "串口自动探测到地址 4") {
            c2.link.value.status == LinkStatus.CONNECTED &&
                c2.board.value.address == 4 &&
                c2.board.value.privateVersion == listOf(2, 0)
        }
        c2.disconnect()
        waitFor(what = "断开") { c2.link.value.status == LinkStatus.DISCONNECTED }
        waitFor(what = "传输关闭") { transport.closed }
    }

    @Test fun `physical UART upgrades from NO_BOARD on reply and downgrades back on empty scan`() {
        val transport = PhysicalSimTransport(target = null)
        val c2 = AppController(store, TransportFactory { OpenedTransport(transport) }, scope, exportDir, "test")
        c2.connect()
        waitFor(what = "初始无板卡") { c2.link.value.status == LinkStatus.NO_BOARD }
        transport.target = SimulatedBus(SimConfig(boards = listOf(SimBoardConfig(1)), responseDelayMs = 1))
        c2.readVersion()
        waitFor(what = "收到回复转为已连接") {
            c2.link.value.status == LinkStatus.CONNECTED &&
                c2.board.value.privateVersion == listOf(2, 0) &&
                c2.busy.value == null
        }
        transport.target = null
        c2.scan()
        waitFor(what = "扫描完成") { !c2.scan.value.running && c2.scan.value.finishedAtMillis != null }
        waitFor(what = "空扫描降级为 NO_BOARD") { c2.link.value.status == LinkStatus.NO_BOARD }
        c2.disconnect()
        waitFor(what = "断开") { c2.link.value.status == LinkStatus.DISCONNECTED }
        waitFor(what = "传输关闭") { transport.closed }
    }

    @Test fun `disconnect while probing closes transport and stays DISCONNECTED`() {
        store.update { it.copy(scanTimeoutMs = 120) }
        val transport = PhysicalSimTransport(target = null)
        val c2 = AppController(store, TransportFactory { OpenedTransport(transport) }, scope, exportDir, "test")
        c2.connect()
        waitFor(what = "进入探测中") { c2.link.value.status == LinkStatus.PROBING }
        c2.disconnect()
        waitFor(what = "探测中断开") { c2.link.value.status == LinkStatus.DISCONNECTED }
        waitFor(what = "传输关闭") { transport.closed }
        Thread.sleep(300)
        assertEquals(LinkStatus.DISCONNECTED, c2.link.value.status)
    }

    @Test fun `IO failure while probing transitions immediately to LOST`() {
        val failing = object : ByteTransport {
            override val kind = TransportKind.TCP
            override val description = "TCP 127.0.0.1:0"
            override fun write(bytes: ByteArray) {}
            override fun read(buffer: ByteArray, timeoutMs: Int): Int = throw java.io.IOException("对端已关闭连接")
            override fun drainInput() = ByteArray(0)
            override fun close() {}
        }
        val c2 = AppController(store, TransportFactory { OpenedTransport(failing) }, scope, exportDir, "test")
        c2.connect()
        waitFor(what = "探测期 I/O 异常转 LOST") { c2.link.value.status == LinkStatus.LOST }
        assertTrue(c2.link.value.message.contains("对端已关闭连接"))
    }

    @Test fun `protocol error or unmatched frame does not mark NO_BOARD as CONNECTED`() {
        // Replies to 0x12 with a valid-checksum 0x12 frame whose unused feedback bits 12..15 are non-zero -> ProtocolException.
        val malformed = object : ByteTransport {
            override val kind = TransportKind.UART
            override val description = "/dev/ttyS1"
            private var reply: ByteArray = ByteArray(0)
            override fun write(bytes: ByteArray) {
                if (bytes.size >= 4 && (bytes[0].toInt() and 0xFF) == 0xDC && (bytes[3].toInt() and 0xFF) == 0x12) {
                    reply = cn.tuodi.lockboard.core.protocol.FrameCodec.encode(1, 0x12, listOf(0xFF, 0xFF), direction = cn.tuodi.lockboard.core.protocol.Direction.BOARD_TO_HOST)
                        .map { it.toByte() }.toByteArray()
                }
            }
            override fun read(buffer: ByteArray, timeoutMs: Int): Int {
                if (reply.isEmpty()) {
                    Thread.sleep(timeoutMs.coerceIn(1, 50).toLong())
                    return 0
                }
                val n = minOf(buffer.size, reply.size)
                System.arraycopy(reply, 0, buffer, 0, n)
                reply = reply.copyOfRange(n, reply.size)
                return n
            }
            override fun drainInput() = ByteArray(0)
            override fun close() {}
        }
        val c2 = AppController(store, TransportFactory { OpenedTransport(malformed) }, scope, exportDir, "test")
        val seen = CopyOnWriteArrayList<LinkStatus>()
        val watch = scope.launch { c2.link.collect { seen += it.status } }
        try {
            c2.connect()
            waitFor(what = "畸形回复不升为 CONNECTED") { c2.link.value.status == LinkStatus.NO_BOARD }
            c2.sendFrame(1, 0x12, "")
            waitFor(what = "终端组帧发送完成") { c2.lastResult.value.contains("数据 [FF FF]") }
            assertEquals(LinkStatus.NO_BOARD, c2.link.value.status)
            c2.sendRawHex("DC 02 01 12 00 13", 80)
            waitFor(what = "原始 HEX 发送完成") { c2.lastResult.value.contains("收到 1 个私有帧") }
            assertEquals(LinkStatus.NO_BOARD, c2.link.value.status)
            assertFalse(LinkStatus.CONNECTED in seen)
        } finally {
            watch.cancel()
        }
    }

    @Test fun `MODBUS exception reply still confirms board online during probe and scan`() {
        // Replies to MODBUS 03(0001) at address 1 with MODBUS exception 02 (illegal data address), silent on private protocol.
        val modbusExOnly = object : ByteTransport {
            override val kind = TransportKind.UART
            override val description = "/dev/ttyS1"
            private var reply: ByteArray = ByteArray(0)
            override fun write(bytes: ByteArray) {
                if (bytes.isNotEmpty() && (bytes[0].toInt() and 0xFF) == 1 && (bytes[1].toInt() and 0xFF) == 0x03) {
                    val frame = Crc16.append(listOf(1, 0x83, 0x02))
                    reply = frame.map { it.toByte() }.toByteArray()
                }
            }
            override fun read(buffer: ByteArray, timeoutMs: Int): Int {
                if (reply.isEmpty()) {
                    Thread.sleep(timeoutMs.coerceIn(1, 50).toLong())
                    return 0
                }
                val n = minOf(buffer.size, reply.size)
                System.arraycopy(reply, 0, buffer, 0, n)
                reply = reply.copyOfRange(n, reply.size)
                return n
            }
            override fun drainInput() = ByteArray(0)
            override fun close() {}
        }
        val c2 = AppController(store, TransportFactory { OpenedTransport(modbusExOnly) }, scope, exportDir, "test")
        c2.connect()
        waitFor(what = "MODBUS 异常回复确认在线") { c2.link.value.status == LinkStatus.CONNECTED }
        c2.scan()
        waitFor(what = "扫描完成") { !c2.scan.value.running && c2.scan.value.finishedAtMillis != null }
        assertEquals(LinkStatus.CONNECTED, c2.link.value.status)
        assertTrue(c2.scan.value.online.any { it.address == 1 && it.modbusException == 2 })
    }
}
