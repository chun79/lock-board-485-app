package cn.tuodi.lockboard.core.protocol

/** A structurally complete frame. Bytes are unsigned values 0..255. */
data class Frame(
    val direction: Direction,
    val address: Int,
    val command: Int,
    val data: List<Int>,
    /** Checksum byte as received. */
    val checksum: Int,
) {
    val expectedChecksum: Int get() = FrameCodec.checksum(address, command, data)
    val checksumValid: Boolean get() = checksum == expectedChecksum
    val raw: List<Int> get() = listOf(Protocol.HEAD, direction.code, address, command, data.size) + data + checksum

    fun describe(): String = buildString {
        append(if (direction == Direction.HOST_TO_BOARD) "主机→板" else "板→主机")
        append(" 地址").append(address)
        append(" 指令0x").append(command.hex2()).append('(').append(Cmd.describe(command)).append(')')
        append(" LEN=").append(data.size)
        if (data.isNotEmpty()) append(" 数据[").append(data.toHex()).append(']')
        if (!checksumValid) append(" 校验错误(收到").append(checksum.hex2()).append("，应为").append(expectedChecksum.hex2()).append(')')
    }
}

object FrameCodec {
    fun checksum(address: Int, command: Int, data: List<Int>): Int =
        (address + command + data.size + data.sum()) and 0xFF

    /**
     * Encodes a frame. [checksumOverride] exists only for robustness tests that deliberately send
     * a corrupted frame.
     */
    fun encode(
        address: Int,
        command: Int,
        data: List<Int> = emptyList(),
        direction: Direction = Direction.HOST_TO_BOARD,
        checksumOverride: Int? = null,
    ): List<Int> {
        require(address in 0..0xFF) { "地址超出 0..255：$address" }
        require(command in 0..0xFF) { "指令超出 0..255：$command" }
        require(data.size <= 0xFF) { "数据段超过 255 字节" }
        require(data.all { it in 0..0xFF }) { "数据字节超出 0..255" }
        val sum = checksumOverride ?: checksum(address, command, data)
        require(sum in 0..0xFF) { "校验值超出 0..255" }
        return listOf(Protocol.HEAD, direction.code, address, command, data.size) + data + sum
    }

    fun encodeBytes(address: Int, command: Int, data: List<Int> = emptyList()): ByteArray =
        encode(address, command, data).toByteArray()

    /** Decodes exactly one frame from [bytes]; returns null when the bytes are not one complete frame. */
    fun decodeSingle(bytes: List<Int>): Frame? {
        val events = FrameParser().feed(bytes)
        val frames = events.mapNotNull {
            when (it) {
                is ParseEvent.FrameReceived -> it.frame
                is ParseEvent.BadChecksum -> it.frame
                is ParseEvent.Garbage -> return null
            }
        }
        return frames.singleOrNull()?.takeIf { it.raw.size == bytes.size }
    }
}
