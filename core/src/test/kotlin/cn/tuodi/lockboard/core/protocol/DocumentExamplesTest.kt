package cn.tuodi.lockboard.core.protocol

import cn.tuodi.lockboard.core.modbus.Crc16
import cn.tuodi.lockboard.core.modbus.Modbus
import cn.tuodi.lockboard.core.modbus.ModbusReply
import cn.tuodi.lockboard.core.modbus.ModbusRequest
import cn.tuodi.lockboard.core.modbus.ModbusResponseLocator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every example frame of docs/12路锁控板私有通讯协议-20261006.pdf and docs/12路锁控板MODBUS-RTU协议-20261006.pdf. */
class DocumentExamplesTest {
    private fun h(s: String) = parseHex(s)
    private fun reply(s: String): Frame = FrameCodec.decodeSingle(h(s))!!

    // ------------------------------------------------------------------ private protocol

    @Test fun `private requests encode exactly as in the document`() {
        assertEquals(h("DC 02 01 01 00 02"), Command.GetVersion(1).encode())
        assertEquals(h("DC 02 01 10 01 01 13"), Command.UnlockOne(1, 1).encode())
        assertEquals(h("DC 02 01 11 01 01 14"), Command.ReadOne(1, 1).encode())
        assertEquals(h("DC 02 01 11 01 0C 1F"), Command.ReadOne(1, 12).encode())
        assertEquals(h("DC 02 01 12 00 13"), Command.ReadAll(1).encode())
        assertEquals(h("DC 02 01 14 00 15"), Command.UnlockAll(1).encode())
        assertEquals(h("DC 02 01 15 02 00 07 1F"), Command.UnlockMany(1, LockMask.of(listOf(1, 2, 3))).encode())
        assertEquals(h("DC 02 01 16 02 01 01 1B"), Command.SetOutput(1, 1, true).encode())
        assertEquals(h("DC 02 01 16 02 01 00 1A"), Command.SetOutput(1, 1, false).encode())
        assertEquals(h("DC 02 01 16 04 01 01 00 01 1E"), Command.SetOutputTimed(1, 1, 1).encode())
        assertEquals(h("DC 02 01 16 04 04 01 00 05 25"), Command.SetOutputTimed(1, 4, 5).encode())
        assertEquals(h("DC 02 01 16 04 0C 01 00 3C 64"), Command.SetOutputTimed(1, 12, 60).encode())
        assertEquals(h("DC 02 01 16 04 04 00 00 00 1F"), Command.Custom(1, Cmd.SET_OUTPUT, listOf(4, 0, 0, 0)).encode())
        assertEquals(h("DC 02 01 18 02 00 07 22"), Command.UnlockSimultaneous(1, 0x0007).encode())
        assertEquals(h("DC 02 01 1A 02 00 07 24"), Command.SetOutputs(1, 0x0007).encode())
        assertEquals(h("DC 02 01 1A 02 00 00 1D"), Command.SetOutputs(1, 0).encode())
    }

    @Test fun `private replies decode as in the document`() {
        assertEquals(VersionReply(2, 0), Command.GetVersion(1).decode(reply("DC FE 01 01 02 02 00 06")))
        assertEquals(SingleLockReply(1, true), Command.UnlockOne(1, 1).decode(reply("DC FE 01 10 02 01 01 15")))
        assertEquals(SingleLockReply(1, true), Command.ReadOne(1, 1).decode(reply("DC FE 01 11 02 01 01 16")))
        assertEquals(SingleLockReply(12, false), Command.ReadOne(1, 12).decode(reply("DC FE 01 11 02 0C 00 20")))
        assertEquals(0, Command.ReadAll(1).decode(reply("DC FE 01 12 02 00 00 15")).mask)
        assertEquals(listOf(1, 12), LockMask.channels(Command.ReadAll(1).decode(reply("DC FE 01 12 02 08 01 1E")).mask))
        assertEquals(0x0FFF, Command.UnlockAll(1).decode(reply("DC FE 01 14 02 0F FF 25")).mask)
        assertEquals(7, Command.UnlockMany(1, 7).decode(reply("DC FE 01 15 02 00 07 1F")).mask)
        assertEquals(AckReply, Command.SetOutput(1, 1, true).decode(reply("DC FE 01 16 01 01 19")))
        assertEquals(7, Command.UnlockSimultaneous(1, 7).decode(reply("DC FE 01 18 02 00 07 22")).mask)
        assertEquals(AckReply, Command.SetOutputs(1, 7).decode(reply("DC FE 01 1A 01 01 1D")))
    }

    @Test fun `reply lock id must match and LEN must be 2`() {
        val wrongId = FrameCodec.decodeSingle(FrameCodec.encode(1, Cmd.READ_ONE, listOf(4, 0), Direction.BOARD_TO_HOST))!!
        assertTrue(runCatching { Command.ReadOne(1, 3).decode(wrongId) }.exceptionOrNull() is ProtocolException)
        val short = FrameCodec.decodeSingle(FrameCodec.encode(1, Cmd.UNLOCK_ONE, listOf(1), Direction.BOARD_TO_HOST))
        assertNull("LEN=1 回复不再合法，解析器按伪帧头丢弃", short)
        val upper = FrameCodec.decodeSingle(FrameCodec.encode(1, Cmd.READ_ALL, listOf(0x80, 0x01), Direction.BOARD_TO_HOST))!!
        assertTrue(runCatching { Command.ReadAll(1).decode(upper) }.exceptionOrNull() is ProtocolException)
        val ack0 = FrameCodec.decodeSingle(FrameCodec.encode(1, Cmd.SET_OUTPUT, listOf(0), Direction.BOARD_TO_HOST))!!
        assertTrue(runCatching { Command.SetOutput(1, 1, true).decode(ack0) }.exceptionOrNull() is ProtocolException)
    }

    @Test fun `checksum example of section 6`() {
        assertEquals(0x25, FrameCodec.checksum(0x01, 0x16, listOf(0x04, 0x01, 0x00, 0x05)))
    }

    @Test fun `lock mask bit mapping`() {
        assertEquals(listOf(0x00, 0x07), LockMask.toBytes(LockMask.of(listOf(1, 2, 3))))
        assertEquals(listOf(0x0F, 0xFF), LockMask.toBytes(LockMask.of(1..12)))
        assertEquals(listOf(0x08, 0x01), LockMask.toBytes(LockMask.of(listOf(1, 12))))
    }

    @Test fun `hex parsing accepts common notations`() {
        assertEquals(listOf(0xDC, 0x02, 0x01), parseHex("0xdc,0x02 01"))
        assertEquals(listOf(0xDC, 0x02, 0x01), parseHex("DC0201"))
        assertTrue(runCatching { parseHex("DC0") }.isFailure)
    }

    // ------------------------------------------------------------------ MODBUS RTU

    @Test fun `modbus requests encode exactly as in the document`() {
        assertEquals(h("01 02 00 01 00 0C 29 CF"), ModbusRequest.ReadDiscreteInputs(1, 1, 12).encode())
        assertEquals(h("01 02 00 0C 00 01 79 C9"), ModbusRequest.ReadDiscreteInputs(1, 12, 1).encode())
        assertEquals(h("01 03 00 01 00 01 D5 CA"), ModbusRequest.ReadHoldingRegisters(1, 1, 1).encode())
        assertEquals(h("01 03 00 02 00 02 65 CB"), ModbusRequest.ReadHoldingRegisters(1, 2, 2).encode())
        assertEquals(h("01 03 00 04 00 02 85 CA"), ModbusRequest.ReadHoldingRegisters(1, 4, 2).encode())
        assertEquals(h("01 05 00 0C FF 00 4C 39"), ModbusRequest.WriteSingleCoil(1, 12).encode())
        assertEquals(h("01 05 00 01 00 00 9C 0A"), ModbusRequest.WriteSingleCoil(1, 1, 0).encode())
        assertEquals(h("01 06 00 04 00 01 09 CB"), ModbusRequest.WriteSingleRegister(1, 4, 1).encode())
        assertEquals(h("01 06 00 04 00 00 C8 0B"), ModbusRequest.WriteSingleRegister(1, 4, 0).encode())
        assertEquals(h("01 06 00 06 0F FF 2C 7B"), ModbusRequest.WriteSingleRegister(1, 6, 0x0FFF).encode())
        assertEquals(h("01 0F 00 0C 00 01 01 01 FF 56"), ModbusRequest.WriteMultipleCoils(1, 12, 1, 1).encode())
        assertEquals(h("01 0F 00 01 00 0C 02 FF 0F E5 95"), ModbusRequest.WriteMultipleCoils(1, 1, 12, 0x0FFF).encode())
        assertEquals(h("01 10 00 04 00 02 04 00 03 00 00 02 5C"), ModbusRequest.WriteMultipleRegisters(1, 4, listOf(3, 0)).encode())
        assertEquals(h("01 10 00 06 00 02 04 08 01 00 00 20 25"), ModbusRequest.WriteMultipleRegisters(1, 6, listOf(0x0801, 0)).encode())
        assertEquals(h("01 10 00 08 00 02 04 00 01 00 01 62 09"), ModbusRequest.WriteMultipleRegisters(1, 8, listOf(1, 1)).encode())
        assertEquals(h("01 10 00 08 00 03 06 00 04 00 01 00 05 07 69"), ModbusRequest.WriteMultipleRegisters(1, 8, listOf(4, 1, 5)).encode())
        assertEquals(h("01 10 00 08 00 03 06 00 0C 00 01 00 3C 26 BA"), ModbusRequest.WriteMultipleRegisters(1, 8, listOf(12, 1, 60)).encode())
        assertEquals(h("01 10 00 08 00 03 06 00 04 00 00 00 00 96 AA"), ModbusRequest.WriteMultipleRegisters(1, 8, listOf(4, 0, 0)).encode())
        assertEquals(h("01 10 00 08 00 03 06 00 04 00 01 00 00 C7 6A"), ModbusRequest.WriteMultipleRegisters(1, 8, listOf(4, 1, 0)).encode())
        assertEquals(h("01 10 00 08 00 02 04 00 0D 00 01 A2 0A"), ModbusRequest.WriteMultipleRegisters(1, 8, listOf(13, 1)).encode())
        assertEquals(h("00 10 00 08 00 03 06 00 01 00 01 00 01 C8 2B"), ModbusRequest.WriteMultipleRegisters(0, 8, listOf(1, 1, 1)).encode())
    }

    private fun decode(req: ModbusRequest, resp: String): ModbusReply {
        val frame = h(resp)
        assertTrue(Crc16.valid(frame))
        val m = ModbusResponseLocator.find(frame, req)
        assertNotNull("无法定位回复 $resp", m)
        return if (frame[1] == (req.function or 0x80)) ModbusReply.Exception(req.function, frame[2]) else req.decodeNormal(m!!.frame)
    }

    @Test fun `modbus responses decode as in the document`() {
        assertEquals(0x0FFF, (decode(ModbusRequest.ReadDiscreteInputs(1, 1, 12), "01 02 02 FF 0F B8 4C") as ModbusReply.Bits).lockMask)
        assertEquals(LockMask.bit(12), (decode(ModbusRequest.ReadDiscreteInputs(1, 12, 1), "01 02 01 01 60 48") as ModbusReply.Bits).lockMask)
        assertEquals(listOf(2), (decode(ModbusRequest.ReadHoldingRegisters(1, 1, 1), "01 03 02 00 02 39 85") as ModbusReply.Registers).values)
        assertEquals(listOf(0x0FFF, 0), (decode(ModbusRequest.ReadHoldingRegisters(1, 2, 2), "01 03 04 0F FF 00 00 C9 17") as ModbusReply.Registers).values)
        assertEquals(listOf(0, 0), (decode(ModbusRequest.ReadHoldingRegisters(1, 4, 2), "01 03 04 00 00 00 00 FA 33") as ModbusReply.Registers).values)
        assertTrue(decode(ModbusRequest.WriteSingleCoil(1, 12), "01 05 00 0C FF 00 4C 39") is ModbusReply.WriteAck)
        assertTrue(decode(ModbusRequest.WriteSingleRegister(1, 4, 1), "01 06 00 04 00 01 09 CB") is ModbusReply.WriteAck)
        assertTrue(decode(ModbusRequest.WriteMultipleCoils(1, 12, 1, 1), "01 0F 00 0C 00 01 54 08") is ModbusReply.WriteAck)
        assertTrue(decode(ModbusRequest.WriteMultipleCoils(1, 1, 12, 0x0FFF), "01 0F 00 01 00 0C 04 0E") is ModbusReply.WriteAck)
        assertTrue(decode(ModbusRequest.WriteMultipleRegisters(1, 4, listOf(3, 0)), "01 10 00 04 00 02 00 09") is ModbusReply.WriteAck)
        assertTrue(decode(ModbusRequest.WriteMultipleRegisters(1, 6, listOf(0x0801, 0)), "01 10 00 06 00 02 A1 C9") is ModbusReply.WriteAck)
        assertTrue(decode(ModbusRequest.WriteMultipleRegisters(1, 8, listOf(1, 1)), "01 10 00 08 00 02 C0 0A") is ModbusReply.WriteAck)
        assertTrue(decode(ModbusRequest.WriteMultipleRegisters(1, 8, listOf(4, 1, 5)), "01 10 00 08 00 03 01 CA") is ModbusReply.WriteAck)
    }

    @Test fun `modbus exceptions decode as in the document`() {
        assertEquals(ModbusReply.Exception(5, 3), decode(ModbusRequest.WriteSingleCoil(1, 1, 0), "01 85 03 02 91"))
        assertEquals(ModbusReply.Exception(0x10, 3), decode(ModbusRequest.WriteMultipleRegisters(1, 8, listOf(4, 1, 0)), "01 90 03 0C 01"))
        assertEquals(ModbusReply.Exception(0x10, 2), decode(ModbusRequest.WriteMultipleRegisters(1, 8, listOf(13, 1)), "01 90 02 CD C1"))
    }

    @Test fun `document validity checks of requests`() {
        assertNull(ModbusRequest.WriteSingleCoil(1, 12).docViolation())
        assertNotNull(ModbusRequest.WriteSingleCoil(1, 1, 0).docViolation())
        assertNotNull(ModbusRequest.ReadHoldingRegisters(1, 0x0B, 1).docViolation())
        assertNotNull(ModbusRequest.WriteMultipleRegisters(1, 8, listOf(4, 1, 0)).docViolation())
        assertNull(ModbusRequest.WriteMultipleRegisters(1, 8, listOf(4, 0, 0)).docViolation()) // timed "off" ignores seconds
        assertNotNull(ModbusRequest.WriteMultipleCoils(1, 1, 12, 0).docViolation())
        assertNotNull(ModbusRequest.WriteMultipleRegisters(1, 4, listOf(3, 1)).docViolation())
        assertEquals(Modbus.REG_OUTPUT_LO, (ModbusRequest.fromFrame(h("01 06 00 04 00 01 09 CB")) as ModbusRequest.WriteSingleRegister).register)
        assertNull(ModbusRequest.fromFrame(h("01 06 00 04 00 01 09 CC")))
    }
}
