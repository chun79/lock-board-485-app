package cn.tuodi.lockboard.core.protocol

sealed interface ParseEvent {
    /** Complete frame with a valid checksum. */
    data class FrameReceived(val frame: Frame) : ParseEvent

    /** Complete frame whose checksum does not match the protocol algorithm. */
    data class BadChecksum(val frame: Frame) : ParseEvent

    /** Bytes that could not belong to any frame. */
    data class Garbage(val bytes: List<Int>, val reason: String) : ParseEvent
}

/**
 * Incremental parser for a half-duplex RS485 byte stream.
 *
 * - Synchronises on `DC 02` (host frame, e.g. an adapter echo) or `DC FE` (board reply).
 * - Board replies to known commands must carry a LEN allowed by the protocol (see [allowedReplyLengths]);
 *   any other LEN, or a LEN above [maxDataLen], is treated as false sync: one byte is discarded.
 * - On a checksum mismatch, if a complete header pair lies inside the candidate frame the parser
 *   resynchronises there (the first candidate was most likely noise); otherwise the whole candidate
 *   is reported as [ParseEvent.BadChecksum] and consumed.
 * - [flushStalled] drops an incomplete candidate after the line went quiet, so a false header with a
 *   plausible LEN cannot swallow a later short reply forever.
 */
class FrameParser(private val maxDataLen: Int = Protocol.DEFAULT_MAX_DATA_LEN) {
    private val buffer = ArrayList<Int>()

    val pendingBytes: List<Int> get() = buffer.toList()

    fun reset(): List<Int> {
        val pending = buffer.toList()
        buffer.clear()
        return pending
    }

    fun feed(bytes: ByteArray, length: Int = bytes.size): List<ParseEvent> =
        feed(List(length) { bytes[it].toInt() and 0xFF })

    fun feed(bytes: List<Int>): List<ParseEvent> {
        buffer.addAll(bytes)
        return drain()
    }

    /**
     * Called when no byte arrived for a while but an incomplete candidate is buffered: the first
     * byte is declared garbage and parsing resumes on the remainder.
     */
    fun flushStalled(): List<ParseEvent> {
        if (buffer.isEmpty()) return emptyList()
        val events = ArrayList<ParseEvent>()
        events += ParseEvent.Garbage(listOf(buffer.removeAt(0)), "帧不完整且线路已静默，丢弃并重新同步")
        events += drain()
        return events
    }

    private fun drain(): List<ParseEvent> {
        val events = ArrayList<ParseEvent>()
        while (step(events)) Unit
        return events
    }

    /** Returns true when progress was made and parsing should continue. */
    private fun step(events: MutableList<ParseEvent>): Boolean {
        if (buffer.isEmpty()) return false
        val start = findHeader(0)
        if (start < 0) {
            events += ParseEvent.Garbage(buffer.toList(), "无帧头")
            buffer.clear()
            return false
        }
        if (start > 0) {
            events += ParseEvent.Garbage(buffer.subList(0, start).toList(), "帧头前的多余字节")
            buffer.subList(0, start).clear()
        }
        if (buffer.size < 5) return false
        val direction = Direction.of(buffer[1])!!
        val command = buffer[3]
        val len = buffer[4]
        val allowed = if (direction == Direction.BOARD_TO_HOST) allowedReplyLengths(command) else null
        if (len > maxDataLen || (allowed != null && len !in allowed)) {
            events += ParseEvent.Garbage(listOf(buffer.removeAt(0)), "指令0x${command.hex2()} LEN=$len 不合理，判定为伪帧头")
            return true
        }
        val total = 6 + len
        if (buffer.size < total) return false
        val frame = Frame(
            direction = direction,
            address = buffer[2],
            command = command,
            data = buffer.subList(5, 5 + len).toList(),
            checksum = buffer[total - 1],
        )
        if (frame.checksumValid) {
            events += ParseEvent.FrameReceived(frame)
            buffer.subList(0, total).clear()
            return true
        }
        val inner = findInnerHeader(total)
        if (inner > 0) {
            events += ParseEvent.Garbage(buffer.subList(0, inner).toList(), "校验错误且帧内出现新帧头，重新同步")
            buffer.subList(0, inner).clear()
        } else {
            events += ParseEvent.BadChecksum(frame)
            buffer.subList(0, total).clear()
        }
        return true
    }

    /**
     * Index of the first `DC 02` / `DC FE` at or after [from]. A 0xDC in the last buffered position
     * counts as a candidate because its direction byte has not arrived yet.
     */
    private fun findHeader(from: Int): Int {
        for (i in from until buffer.size) {
            if (buffer[i] != Protocol.HEAD) continue
            if (i + 1 >= buffer.size) return i
            if (Direction.of(buffer[i + 1]) != null) return i
        }
        return -1
    }

    /** First complete header pair strictly inside the candidate frame [0, total). */
    private fun findInnerHeader(total: Int): Int {
        for (i in 1 until total - 1) {
            if (buffer[i] == Protocol.HEAD && Direction.of(buffer[i + 1]) != null) return i
        }
        return -1
    }

    companion object {
        /** Reply LEN per the 2026-10-06 private protocol; null for commands without a defined reply. */
        fun allowedReplyLengths(command: Int): IntRange? = when (command) {
            Cmd.GET_VERSION, Cmd.UNLOCK_ONE, Cmd.READ_ONE -> 2..2
            Cmd.READ_ALL, Cmd.UNLOCK_ALL, Cmd.UNLOCK_MANY, Cmd.UNLOCK_SIMULTANEOUS -> 2..2
            Cmd.SET_OUTPUT, Cmd.SET_OUTPUTS -> 1..1
            else -> null
        }
    }
}
