package cn.tuodi.lockboard.core.client

import cn.tuodi.lockboard.core.config.BoardProfile
import cn.tuodi.lockboard.core.config.ChannelProfile
import cn.tuodi.lockboard.core.config.ChannelType
import cn.tuodi.lockboard.core.modbus.Crc16
import cn.tuodi.lockboard.core.modbus.Modbus
import cn.tuodi.lockboard.core.modbus.ModbusReply
import cn.tuodi.lockboard.core.modbus.ModbusRequest
import cn.tuodi.lockboard.core.protocol.Cmd
import cn.tuodi.lockboard.core.protocol.Command
import cn.tuodi.lockboard.core.protocol.Direction
import cn.tuodi.lockboard.core.protocol.FrameCodec
import cn.tuodi.lockboard.core.protocol.LockMask
import cn.tuodi.lockboard.core.protocol.parseHex
import cn.tuodi.lockboard.core.sim.SimBoardConfig
import cn.tuodi.lockboard.core.sim.SimConfig
import cn.tuodi.lockboard.core.sim.SimulatedBus
import cn.tuodi.lockboard.core.tcp.TcpTransport
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

class LockBoardClientTest {
    private val fast = ClientSettings(
        readTimeoutMs = 100, readRetries = 2, perLockPulseMaxMs = 60, simultaneousPulseMs = 15, actionMarginMs = 60,
        interFrameGapMs = 1, lateReplyGuardMs = 80, interByteTimeoutMs = 15, lateReplyMaxAgeMs = 400,
    )
    private val simFast = SimConfig(boards = listOf(SimBoardConfig(1)), pulseOnMs = 10, pulseSettleMs = 5, responseDelayMs = 1, autoCloseMs = 300)
    private val outputProfile = BoardProfile(1)
        .withChannel(11, ChannelProfile(ChannelType.OUTPUT, "灯"))
        .withChannel(12, ChannelProfile(ChannelType.OUTPUT, "继电器"))

    private fun client(
        transport: ByteTransport,
        settings: ClientSettings = fast,
        profile: BoardProfile = outputProfile,
        lockTimed: Int = 0,
    ) = LockBoardClient(transport, settings, CommLog(), SafetyPolicy({ if (it == profile.address) profile else BoardProfile(it) }, { lockTimed }))

    private fun ack16() = FrameCodec.encode(1, Cmd.SET_OUTPUT, listOf(1), Direction.BOARD_TO_HOST)
    private fun regs(vararg v: Int) = Crc16.append(listOf(1, 3, 2 * v.size) + v.flatMap { listOf(it shr 8, it and 0xFF) })

    // ---------------------------------------------------------------- basic exchange

    @Test fun `both protocols read the same board`() = runBlocking {
        val c = client(SimulatedBus(simFast))
        assertEquals(2, c.execute(Command.GetVersion(1)).confirmedReply()!!.version)
        val mv = c.executeModbus(ModbusRequest.ReadHoldingRegisters(1, 1, 1)).confirmedReply() as ModbusReply.Registers
        assertEquals(0x0002, mv.values.single())
        val unlock = c.execute(Command.UnlockOne(1, 5))
        assertEquals(true, unlock.confirmedReply()!!.state)
        assertTrue("0x10 在动作结束后才回复", (unlock as CommResult.Ok).latencyMs >= 10)
        val bits = c.executeModbus(ModbusRequest.ReadDiscreteInputs(1, 1, 12)).confirmedReply() as ModbusReply.Bits
        assertEquals(listOf(5), LockMask.channels(bits.lockMask))
        assertEquals(LockMask.bit(5), c.execute(Command.ReadAll(1)).confirmedReply()!!.mask)
    }

    @Test fun `read timeout is retried, action timeout is outcome unknown and never retried`() = runBlocking {
        val bus = SimulatedBus(simFast)
        val c = client(bus)
        val read = c.execute(Command.ReadAll(7)) as CommResult.Failed
        assertEquals(CommError.TIMEOUT, read.error)
        assertEquals(3, read.attempts)
        val action = c.execute(Command.UnlockOne(7, 1)) as CommResult.Failed
        assertEquals(CommError.OUTCOME_UNKNOWN, action.error)
        assertEquals(1, bus.receivedHostFrames.count { it.command == Cmd.UNLOCK_ONE })
        val mb = c.executeModbus(ModbusRequest.WriteSingleCoil(7, 1)) as CommResult.Failed
        assertEquals(CommError.OUTCOME_UNKNOWN, mb.error)
        assertEquals(1, bus.receivedModbusFrames.size)
    }

    @Test fun `echo and stray private frames are ignored`() = runBlocking {
        val stray = FrameCodec.encode(2, Cmd.READ_ALL, listOf(0, 1), Direction.BOARD_TO_HOST)
        val t = ScriptedTransport { w, _ -> listOf(0L to w, 3L to stray, 6L to FrameCodec.encode(1, Cmd.READ_ALL, listOf(0, 4), Direction.BOARD_TO_HOST)) }
        val c = client(t)
        assertEquals(4, c.execute(Command.ReadAll(1)).confirmedReply()!!.mask)
        val kinds = c.log.snapshot().map { it.kind }
        assertTrue(LogKind.RX_ECHO in kinds && LogKind.RX_STRAY in kinds)
    }

    @Test fun `modbus echo is discarded only when configured`() = runBlocking {
        val echoBus = SimulatedBus(simFast.copy(echo = true))
        val c = client(echoBus, fast.copy(discardTxEcho = true))
        val r = c.executeModbus(ModbusRequest.ReadHoldingRegisters(1, 1, 1))
        assertEquals(listOf(2), (r.confirmedReply() as ModbusReply.Registers).values)
        assertTrue(c.log.snapshot().any { it.kind == LogKind.RX_ECHO })
        // Discard on a non-echoing adapter swallows the identical 06 reply: documented limitation.
        val noEcho = client(SimulatedBus(simFast), fast.copy(discardTxEcho = true))
        assertTrue(noEcho.executeModbus(ModbusRequest.WriteSingleRegister(1, Modbus.REG_PULSE_LO, 0)) is CommResult.Failed)
    }

    @Test fun `modbus exceptions are decoded`() = runBlocking {
        val c = client(SimulatedBus(simFast))
        assertEquals(ModbusReply.Exception(Modbus.FC_WRITE_SINGLE_COIL, 3), c.sendModbusProbe(1, ModbusProbe.COIL_ZERO).confirmedReply())
        assertEquals(ModbusReply.Exception(0x01, 1), c.sendModbusProbe(1, ModbusProbe.UNSUPPORTED_FUNCTION).confirmedReply())
        assertEquals(ModbusReply.Exception(Modbus.FC_READ_HOLDING_REGISTERS, 2), c.sendModbusProbe(1, ModbusProbe.READ_OUT_OF_RANGE).confirmedReply())
        assertEquals(ModbusReply.Exception(Modbus.FC_WRITE_MULTIPLE_REGISTERS, 2), c.sendModbusProbe(1, ModbusProbe.CHANNEL_13).confirmedReply())
        assertEquals(ModbusReply.Exception(Modbus.FC_WRITE_MULTIPLE_REGISTERS, 3), c.sendModbusProbe(1, ModbusProbe.ATOMIC_RESERVED, 0).confirmedReply())
        // Document checks refuse to send illegal requests through the normal path.
        assertEquals(CommError.REJECTED, (c.executeModbus(ModbusRequest.WriteSingleCoil(1, 1, 0)) as CommResult.Failed).error)
    }

    // ---------------------------------------------------------------- safety policy

    private fun rejected(r: CommResult<*>) = r is CommResult.Failed && r.error == CommError.REJECTED

    @Test fun `continuous output only on armed lamp channels, never on locks`() = runBlocking {
        val bus = SimulatedBus(simFast)
        val c = client(bus)
        assertTrue(rejected(c.execute(Command.SetOutput(1, 12, true))))                      // not armed
        c.safety.arm(1)
        assertTrue(rejected(c.execute(Command.SetOutput(1, 1, true))))                       // lock channel
        assertTrue(rejected(c.execute(Command.SetOutputs(1, LockMask.of(listOf(1, 12))))))   // mask with a lock
        assertTrue(rejected(c.executeModbus(ModbusRequest.WriteSingleRegister(1, Modbus.REG_OUTPUT_LO, 1))))
        assertTrue(rejected(c.executeModbus(ModbusRequest.WriteMultipleRegisters(1, Modbus.REG_SINGLE_CHANNEL, listOf(1, 1)))))
        assertTrue(rejected(c.executeModbus(ModbusRequest.WriteMultipleRegisters(1, Modbus.REG_SINGLE_CHANNEL, listOf(1, 1, 5)))))
        assertTrue(bus.receivedHostFrames.none { it.command == Cmd.SET_OUTPUT || it.command == Cmd.SET_OUTPUTS })
        assertTrue(bus.receivedModbusFrames.isEmpty())
        assertTrue(c.execute(Command.SetOutput(1, 12, true)) is CommResult.Ok)
        assertTrue(c.executeModbus(ModbusRequest.WriteMultipleRegisters(1, Modbus.REG_SINGLE_CHANNEL, listOf(11, 1, 2))) is CommResult.Ok)
        assertTrue(c.execute(Command.SetOutput(1, 1, false)) is CommResult.Ok)               // off is always allowed
    }

    @Test fun `writes that turn timed outputs into permanent ones are refused`() = runBlocking {
        val c = client(SimulatedBus(simFast))
        c.safety.arm(1)
        assertTrue(rejected(c.executeModbus(ModbusRequest.WriteSingleRegister(1, Modbus.REG_OUTPUT_HI, 0))))
        assertTrue(rejected(c.executeModbus(ModbusRequest.WriteMultipleRegisters(1, Modbus.REG_OUTPUT_HI, listOf(0, 0)))))
        assertTrue(rejected(c.executeModbus(ModbusRequest.WriteSingleRegister(1, Modbus.REG_PULSE_HI, 0))))
        assertTrue(c.executeModbus(ModbusRequest.WriteMultipleRegisters(1, Modbus.REG_OUTPUT_LO, listOf(0, 0))) is CommResult.Ok)
    }

    @Test fun `lock channels may only be timed within the limit and never re-timed while energised`() = runBlocking {
        val none = client(SimulatedBus(simFast))
        none.safety.arm(1)
        assertTrue(rejected(none.execute(Command.SetOutputTimed(1, 2, 1))))
        val c = client(SimulatedBus(simFast), lockTimed = 2)
        assertTrue(rejected(c.execute(Command.SetOutputTimed(1, 2, 1))))      // not armed
        c.safety.arm(1)
        assertTrue(rejected(c.execute(Command.SetOutputTimed(1, 2, 3))))      // above the limit
        assertTrue(c.execute(Command.SetOutputTimed(1, 2, 1)) is CommResult.Ok)
        // Re-timing (either protocol) would extend the on-time.
        assertTrue(rejected(c.execute(Command.SetOutputTimed(1, 2, 1))))
        assertTrue(rejected(c.executeModbus(ModbusRequest.WriteMultipleRegisters(1, 8, listOf(2, 1, 1)))))
        delay(1300)
        c.readOutputs(1)
        assertFalse(c.outputs.isTracked(OutputKey(1, 2)))
        assertTrue(c.execute(Command.SetOutputTimed(1, 2, 1)) is CommResult.Ok)
    }

    @Test fun `broadcast never energises and pulses need confirmation`() = runBlocking {
        val bus = SimulatedBus(simFast.copy(boards = listOf(SimBoardConfig(1), SimBoardConfig(2))))
        val c = client(bus)
        c.safety.arm(1)
        assertTrue(rejected(c.executeModbus(ModbusRequest.WriteSingleRegister(0, Modbus.REG_OUTPUT_LO, LockMask.bit(12)))))
        assertTrue(rejected(c.executeModbus(ModbusRequest.WriteSingleCoil(0, 1))))
        val ok = c.executeModbus(ModbusRequest.WriteSingleCoil(0, 1), ExecOptions(broadcastConfirmed = true))
        assertEquals(ModbusReply.BroadcastSent, ok.confirmedReply())
        c.awaitPulseIdle(1)
        delay(30)
        assertTrue(bus.boardStates().all { it.lockOpen[0] })
    }

    @Test fun `lock-down blocks every energising write but allows switching off`() = runBlocking {
        val c = client(SimulatedBus(simFast))
        c.safety.arm(1)
        c.safety.lockDown()
        assertFalse(c.safety.arm(1))
        assertTrue(rejected(c.execute(Command.SetOutput(1, 12, true))))
        assertTrue(c.execute(Command.SetOutput(1, 12, false)) is CommResult.Ok)
    }

    @Test fun `lock-down also refuses new pulses of both protocols`() = runBlocking {
        val bus = SimulatedBus(simFast)
        val c = client(bus)
        c.safety.lockDown()
        assertTrue(rejected(c.execute(Command.UnlockOne(1, 1))))
        assertTrue(rejected(c.execute(Command.UnlockAll(1))))
        assertTrue(rejected(c.executeModbus(ModbusRequest.WriteSingleCoil(1, 1))))
        assertTrue(rejected(c.executeModbus(ModbusRequest.Custom(1, Modbus.FC_WRITE_SINGLE_COIL, listOf(0, 1, 0xFF, 0)))))
        assertTrue(c.execute(Command.ReadAll(1)) is CommResult.Ok)
        assertTrue(bus.receivedHostFrames.none { it.command == Cmd.UNLOCK_ONE } && bus.receivedModbusFrames.isEmpty())
    }

    @Test fun `a MODBUS Custom pulse is paced like a typed one`() = runBlocking {
        val c = client(SimulatedBus(simFast))
        val start = System.nanoTime()
        assertTrue(c.executeModbus(ModbusRequest.Custom(1, Modbus.FC_WRITE_SINGLE_COIL, listOf(0, 3, 0xFF, 0))) is CommResult.Ok)
        assertTrue(c.executeModbus(ModbusRequest.WriteSingleCoil(1, 4)) is CommResult.Ok)
        assertTrue((System.nanoTime() - start) / 1_000_000 >= 110)
    }

    @Test fun `session carry keeps unconfirmed outputs and quarantine`() = runBlocking {
        val c1 = client(SimulatedBus(simFast))
        c1.safety.arm(1)
        c1.execute(Command.SetOutput(1, 12, true))
        c1.executeModbus(ModbusRequest.WriteSingleCoil(1, 2))
        val carry = c1.exportCarry()
        assertEquals(listOf(OutputKey(1, 12)), carry.outputs)
        assertTrue(carry.pulseBusyUntil.isNotEmpty())
        val bus2 = SimulatedBus(simFast)
        val c2 = client(bus2)
        c2.importCarry(carry)
        assertTrue(c2.outputs.isTracked(OutputKey(1, 12)))
        assertTrue(c2.pulseFreeAt(1) > System.nanoTime())
        c2.readOutputs(1)
        assertFalse(c2.outputs.isTracked(OutputKey(1, 12)))
    }

    @Test fun `atomicity probe requires every output off and an authorised low word`() = runBlocking {
        val c = client(SimulatedBus(simFast))
        // Low word with a lamp bit needs the wiring confirmation.
        assertTrue(rejected(c.sendModbusProbe(1, ModbusProbe.ATOMIC_RESERVED, LockMask.bit(12))))
        c.safety.arm(1)
        assertEquals(ModbusReply.Exception(Modbus.FC_WRITE_MULTIPLE_REGISTERS, 3), c.sendModbusProbe(1, ModbusProbe.ATOMIC_RESERVED, LockMask.bit(12)).confirmedReply())
        c.readOutputs(1)
        c.execute(Command.SetOutput(1, 11, true))
        assertTrue(rejected(c.sendModbusProbe(1, ModbusProbe.ATOMIC_RESERVED, 0)))
    }

    @Test fun `atomicity probe is refused when the board reads back an output this client never registered`() = runBlocking {
        val bus = SimulatedBus(simFast)
        val c1 = client(bus)
        c1.safety.arm(1)
        c1.execute(Command.SetOutput(1, 12, true))
        val c2 = client(bus) // new session, nothing registered
        c2.safety.arm(1)
        val before = bus.receivedModbusFrames.size
        assertTrue(rejected(c2.sendModbusProbe(1, ModbusProbe.ATOMIC_RESERVED, 0)))
        // Only the read-back went out, no write.
        assertTrue(bus.receivedModbusFrames.drop(before).all { it[1] == Modbus.FC_READ_HOLDING_REGISTERS })
    }

    @Test fun `a pulse whose write throws still blocks the next pulse and is carried`() = runBlocking {
        // The adapter may have forwarded the frame before the host-side write failed (unplugged mid-write).
        val failing = object : ByteTransport {
            override val kind = TransportKind.USB
            override val description = "写入失败"
            override fun write(bytes: ByteArray) = throw java.io.IOException("拔出")
            override fun read(buffer: ByteArray, timeoutMs: Int) = 0
            override fun drainInput() = ByteArray(0)
            override fun close() {}
        }
        val c = client(failing)
        val r = c.executeModbus(ModbusRequest.WriteSingleCoil(1, 3))
        assertTrue(r is CommResult.Failed)
        assertTrue(c.pulseFreeAt(1) > System.nanoTime())
        assertTrue(c.exportCarry().pulseBusyUntil.containsKey(1))
    }

    @Test fun `registration generations are unique across sessions`() {
        val k = OutputKey(1, 12)
        val t1 = OutputTracker().apply { registerPossiblyOn(listOf(k)) }
        val t2 = OutputTracker().apply { registerPossiblyOn(listOf(k)) }
        val g1 = t1.generation(k)!!
        assertFalse(t2.operatorConfirmedOff(k, g1))
        assertTrue(t2.isTracked(k))
    }

    @Test fun `operator confirmation only clears the registration it observed`() {
        val t = OutputTracker()
        val k = OutputKey(1, 12)
        t.registerPossiblyOn(listOf(k))
        val g = t.generation(k)!!
        t.registerPossiblyOn(listOf(k)) // energised again while the dialog was open
        assertFalse(t.operatorConfirmedOff(k, g))
        assertTrue(t.isTracked(k))
        assertTrue(t.operatorConfirmedOff(k, t.generation(k)!!))
        assertFalse(t.isTracked(k))
    }

    @Test fun `physical minimums keep pulse budgets at firmware timing`() {
        val s = ClientSettings(perLockPulseMaxMs = 50, simultaneousPulseMs = 20, actionMarginMs = 0).withPhysicalMinimums()
        assertEquals(1400, s.perLockPulseMaxMs)
        assertEquals(350, s.simultaneousPulseMs)
        assertEquals(500, s.actionMarginMs)
    }

    @Test fun `queued output command is re-checked at transmission`() = runBlocking {
        val bus = SimulatedBus(simFast)
        val c = client(bus)
        c.safety.arm(1)
        val blocker = launch { c.execute(Command.ReadAll(9)) }
        delay(30)
        val queued = async { c.execute(Command.SetOutput(1, 12, true)) }
        delay(30)
        c.safety.onProfileChanged(1)
        blocker.join()
        assertTrue(rejected(queued.await()))
        assertTrue(bus.receivedHostFrames.none { it.command == Cmd.SET_OUTPUT })
        assertTrue(c.outputs.energized().isEmpty())
    }

    // ---------------------------------------------------------------- output tracking

    @Test fun `ACKs never clear the registration, only a trusted read-back does`() = runBlocking {
        val c = client(SimulatedBus(simFast))
        c.safety.arm(1)
        c.execute(Command.SetOutput(1, 12, true))
        assertEquals(OutputState.POSSIBLY_ON, c.outputs.state.value[OutputKey(1, 12)])
        c.readOutputs(1)
        assertEquals(OutputState.READBACK_ON, c.outputs.state.value[OutputKey(1, 12)])
        assertNotNull(c.execute(Command.SetOutput(1, 12, false)).confirmedReply())
        assertTrue("关断 ACK 不清账", c.outputs.isTracked(OutputKey(1, 12)))
        c.readOutputs(1)
        assertFalse(c.outputs.isTracked(OutputKey(1, 12)))
    }

    @Test fun `cleanup switches off and confirms by read-back, timers included`() = runBlocking {
        val bus = SimulatedBus(simFast)
        val c = client(bus)
        c.safety.arm(1)
        c.execute(Command.SetOutputs(1, LockMask.of(listOf(11, 12))))
        c.executeModbus(ModbusRequest.WriteMultipleRegisters(1, 8, listOf(11, 1, 60)))
        val res = c.cleanup()
        assertTrue(res.possiblyOn.isEmpty())
        assertTrue(bus.boardStates().single().outputOn.none { it })
    }

    @Test fun `a stale 03 reply cannot pass for the read-back`() = runBlocking {
        // write 0: 03 query, cancelled; its reply "all outputs off" arrives late.
        // write 1: private 0x16 on (ack). write 2: read-back, answered "channel 12 on".
        val t = ScriptedTransport { _, i ->
            when (i) {
                0 -> listOf(200L to regs(0, 0))
                1 -> listOf(3L to ack16())
                else -> listOf(3L to regs(LockMask.bit(12), 0))
            }
        }
        val c = client(t)
        c.safety.arm(1)
        withTimeoutOrNull(50) { c.executeModbus(ModbusRequest.ReadHoldingRegisters(1, Modbus.REG_FEEDBACK_LO, 2)) }
        c.execute(Command.SetOutput(1, 12, true))
        val rb = c.readOutputs(1) as CommResult.Ok
        assertTrue(rb.confirmed)
        assertEquals(LockMask.bit(12), (rb.reply as ModbusReply.Registers).values[0])
        assertTrue("迟到的全关回复不能清账", c.outputs.isTracked(OutputKey(1, 12)))
        assertTrue(c.log.snapshot().any { it.kind == LogKind.RX_LATE })
    }

    @Test fun `reply to a request written while the key is dirty is ambiguous`() = runBlocking {
        val t = ScriptedTransport { _, i -> if (i == 0) emptyList() else listOf(3L to regs(2)) }
        val c = client(t, fast.copy(readRetries = 0))
        assertTrue(c.executeModbus(ModbusRequest.ReadHoldingRegisters(1, 1, 1)) is CommResult.Failed)
        val second = c.executeModbus(ModbusRequest.ReadHoldingRegisters(1, 1, 1)) as CommResult.Ok
        assertTrue(second.ambiguous)
        assertNull(second.confirmedReply())
        val clean = c.executeModbus(ModbusRequest.ReadHoldingRegisters(1, 1, 1), ExecOptions(requireClean = true)) as CommResult.Ok
        assertTrue(clean.confirmed)
    }

    // ---------------------------------------------------------------- pulse pacing

    @Test fun `a pulse never replaces a running pulse task`() = runBlocking {
        val c = client(SimulatedBus(simFast))
        val start = System.nanoTime()
        assertTrue(c.executeModbus(ModbusRequest.WriteSingleCoil(1, 3)) is CommResult.Ok)
        val second = c.execute(Command.UnlockOne(1, 4))
        val elapsed = (System.nanoTime() - start) / 1_000_000
        assertTrue("第二个脉冲须等第一个动作窗口（≈120 ms）结束：$elapsed ms", elapsed >= 110)
        assertEquals(true, second.confirmedReply()!!.state)
        assertEquals(LockMask.of(listOf(3, 4)), c.execute(Command.ReadAll(1)).confirmedReply()!!.mask)
    }

    // ---------------------------------------------------------------- terminal

    @Test fun `raw terminal rules`() = runBlocking {
        val physical = client(ScriptedTransport(TransportKind.USB) { _, _ -> emptyList() })
        assertNotNull(physical.validateRaw(parseHex("00 11 22")))
        assertNotNull(physical.validateRaw(parseHex("dc 02 01 16 02")))
        assertNotNull(physical.validateRaw(parseHex("dc 02 01 16 02 01 01 1b")))                      // lock ch 1 continuous
        assertNotNull(physical.validateRaw(parseHex("dc 02 01 12 00 13 dc 02 01 12 00 13")))           // two frames
        assertNotNull(physical.validateRaw(Crc16.append(listOf(1, 6, 0, 5, 0, 0))))                    // 0005 alone
        assertNull(physical.validateRaw(parseHex("dc 02 01 12 00 13")))
        assertNull(physical.validateRaw(parseHex("01 03 00 01 00 01 D5 CA")))
        val sim = client(SimulatedBus(simFast))
        assertNull(sim.validateRaw(parseHex("00 11 22")))
        val ex = sim.sendRaw(parseHex("01 03 00 01 00 01 D5 CA"), 100)
        assertEquals(listOf(2), (ex.modbusReply as ModbusReply.Registers).values)
    }

    // ---------------------------------------------------------------- links

    @Test fun `closing the transport marks the client broken`() = runBlocking {
        val bus = SimulatedBus(simFast)
        val c = client(bus)
        bus.close()
        assertEquals(CommError.TRANSPORT, (c.execute(Command.ReadAll(1)) as CommResult.Failed).error)
        assertFalse(c.isOpen)
        assertEquals(CommError.NOT_CONNECTED, (c.executeModbus(ModbusRequest.ReadHoldingRegisters(1, 1, 1)) as CommResult.Failed).error)
    }

    @Test fun `tcp transport against a gateway backed by the simulator`() = runBlocking {
        val bus = SimulatedBus(simFast.copy(boards = listOf(SimBoardConfig(3))))
        val server = ServerSocket(0)
        val pump = thread(isDaemon = true) {
            server.accept().use { s ->
                val input = s.getInputStream()
                val output = s.getOutputStream()
                val reader = thread(isDaemon = true) {
                    val buf = ByteArray(64)
                    try { while (true) { val n = bus.read(buf, 50); if (n > 0) output.write(buf, 0, n) } } catch (_: Exception) {}
                }
                val buf = ByteArray(64)
                while (true) { val n = input.read(buf); if (n < 0) break; bus.write(buf.copyOf(n)) }
                bus.close(); reader.join(500)
            }
        }
        val c = client(TcpTransport.connect("127.0.0.1", server.localPort))
        assertTrue(c.execute(Command.GetVersion(3)) is CommResult.Ok)
        assertTrue(c.executeModbus(ModbusRequest.ReadHoldingRegisters(3, 1, 1)) is CommResult.Ok)
        c.close()
        pump.join(2000)
        server.close()
    }

    @Test fun `tcp backlog beyond the drain budget fails`() {
        val server = ServerSocket(0)
        val feeder = thread(isDaemon = true) { server.accept().use { s -> s.getOutputStream().write(ByteArray(70_000) { 0x55 }); Thread.sleep(1500) } }
        val tcp = TcpTransport.connect("127.0.0.1", server.localPort)
        Thread.sleep(400)
        assertTrue(runCatching { tcp.drainInput() }.exceptionOrNull() is java.io.IOException)
        tcp.close(); feeder.join(2000); server.close()
    }
}
