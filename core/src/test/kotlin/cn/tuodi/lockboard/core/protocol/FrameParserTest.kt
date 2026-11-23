package cn.tuodi.lockboard.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameParserTest {
    private val reply = parseHex("dc fe 01 12 02 00 07 1c")

    private fun frames(events: List<ParseEvent>) = events.filterIsInstance<ParseEvent.FrameReceived>().map { it.frame }

    @Test fun `byte by byte feeding yields one frame`() {
        val p = FrameParser()
        val events = reply.flatMap { p.feed(listOf(it)) }
        assertEquals(1, frames(events).size)
        assertTrue(p.pendingBytes.isEmpty())
    }

    @Test fun `leading garbage is reported then frame parsed`() {
        val events = FrameParser().feed(listOf(0x00, 0xFF, 0x55) + reply)
        assertEquals(listOf(0x00, 0xFF, 0x55), events.filterIsInstance<ParseEvent.Garbage>().single().bytes)
        assertEquals(1, frames(events).size)
    }

    @Test fun `echo and reply in one chunk`() {
        val f = frames(FrameParser().feed(parseHex("dc 02 01 12 00 13") + reply))
        assertEquals(listOf(Direction.HOST_TO_BOARD, Direction.BOARD_TO_HOST), f.map { it.direction })
    }

    @Test fun `implausible LEN for a known reply is treated as false sync`() {
        assertEquals(1, frames(FrameParser().feed(parseHex("dc fe 01 12 05") + reply)).size)
        // 0x10 replies must have LEN 2 (new protocol); LEN 1 is a false header.
        assertEquals(1, frames(FrameParser().feed(parseHex("dc fe 01 10 01") + reply)).size)
    }

    @Test fun `bad checksum frame is reported and consumed`() {
        val events = FrameParser().feed(parseHex("dc fe 01 18 02 00 07 e3") + reply)
        assertEquals(1, events.filterIsInstance<ParseEvent.BadChecksum>().size)
        assertEquals(1, frames(events).size)
    }

    @Test fun `bad checksum candidate with inner header resynchronises`() {
        val events = FrameParser().feed(parseHex("dc fe 05 99 03 aa") + reply)
        assertEquals(1, frames(events).size)
        assertTrue(events.none { it is ParseEvent.BadChecksum })
    }

    @Test fun `stalled partial candidate can be flushed to find a later frame`() {
        val p = FrameParser()
        val first = p.feed(parseHex("dc fe 05 99 09") + parseHex("dc fe 01 16 01 01 19"))
        assertTrue(frames(first).isEmpty())
        val flushed = p.flushStalled()
        assertEquals(Cmd.SET_OUTPUT, frames(flushed).single().command)
    }

    @Test fun `trailing DC is kept as header candidate`() {
        val p = FrameParser()
        val events = p.feed(listOf(0x11, 0xDC))
        assertEquals(listOf(0x11), events.filterIsInstance<ParseEvent.Garbage>().single().bytes)
        assertEquals(listOf(0xDC), p.pendingBytes)
        assertEquals(1, frames(p.feed(reply.drop(1))).size)
    }
}
