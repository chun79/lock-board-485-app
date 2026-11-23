package cn.tuodi.lockboard.transport

import cn.tuodi.lockboard.core.client.ByteTransport
import cn.tuodi.lockboard.core.client.TransportKind
import cn.tuodi.lockboard.core.config.Parity
import cn.tuodi.lockboard.core.config.SerialParams
import java.io.File
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal object NativeSerial {
    val loadError: Throwable? = runCatching { System.loadLibrary("lockboard_serial") }.exceptionOrNull()

    @JvmStatic external fun nativeOpen(path: String, baud: Int, dataBits: Int, parity: Int, stopBits: Int): Int
    @JvmStatic external fun nativeEnableRs485(fd: Int): String
    @JvmStatic external fun nativeRead(fd: Int, buffer: ByteArray, timeoutMs: Int): Int
    @JvmStatic external fun nativeWrite(fd: Int, data: ByteArray, timeoutMs: Int)
    @JvmStatic external fun nativeClose(fd: Int)
}

/**
 * On-board UART (`/dev/ttyS*` etc.) of an industrial Android controller with an RS485 transceiver.
 * Requires the device node to be readable/writable by the app (DAC + SELinux), which is a property of
 * the controller's firmware, not something an app can grant itself.
 */
class UartTransport private constructor(
    private val path: String,
    private var fd: Int,
    private val baud: Int,
    val rs485Status: String?,
) : ByteTransport {
    override val kind = TransportKind.UART
    override val description get() = "$path${if (rs485Status != null) "（内核 RS485 模式）" else ""}"

    /** Serialises native calls so close() can never race a read on a recycled fd. */
    private val io = ReentrantLock()

    private fun checkOpen(): Int = fd.takeIf { it >= 0 } ?: throw IOException("串口已关闭")

    /** Bounded: wire time at [baud] plus 500 ms margin. */
    override fun write(bytes: ByteArray) = io.withLock {
        NativeSerial.nativeWrite(checkOpen(), bytes, (bytes.size * 10_000 / baud + 500).coerceAtMost(10_000))
    }

    override fun read(buffer: ByteArray, timeoutMs: Int): Int =
        io.withLock { NativeSerial.nativeRead(checkOpen(), buffer, timeoutMs.coerceIn(1, 50)) }

    /** Drains until empty; a backlog beyond 64 KiB fails instead of leaking old frames into the next request. */
    override fun drainInput(): ByteArray = io.withLock {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(256)
        while (true) {
            val n = NativeSerial.nativeRead(checkOpen(), buf, 0)
            if (n <= 0) return@withLock out.toByteArray()
            out.write(buf, 0, n)
            if (out.size() >= MAX_DRAIN_BYTES) throw IOException("串口积压数据超过 $MAX_DRAIN_BYTES 字节，总线未清空")
        }
        @Suppress("UNREACHABLE_CODE")
        out.toByteArray()
    }

    override fun close() = io.withLock {
        if (fd >= 0) {
            NativeSerial.nativeClose(fd)
            fd = -1
        }
    }

    companion object {
        private const val MAX_DRAIN_BYTES = 65_536
        private val NAME_PATTERN = Regex("^tty(S|USB|ACM|MT|HS|AMA|SAC|WK|sWK|XRUSB|GS|FIQ|HSL|MSM|THS|AS)\\d+$")

        /** Candidate serial device nodes with their accessibility for this app. */
        fun listCandidates(): List<Pair<String, Boolean>> =
            (File("/dev").listFiles()?.toList() ?: emptyList())
                .filter { NAME_PATTERN.matches(it.name) }
                .sortedBy { it.name }
                .map { it.absolutePath to (it.canRead() && it.canWrite()) }

        @Throws(IOException::class)
        fun open(path: String, params: SerialParams, rs485Ioctl: Boolean): UartTransport {
            NativeSerial.loadError?.let { throw IOException("串口本地库加载失败：${it.message}") }
            val file = File(path)
            if (!file.exists()) throw IOException("设备节点不存在：$path")
            if (!file.canRead() || !file.canWrite()) {
                throw IOException("没有 $path 的读写权限（需主板固件开放该节点及 SELinux 策略；普通手机请改用 USB 转 485）")
            }
            val parity = when (params.parity) { Parity.NONE -> 0; Parity.ODD -> 1; Parity.EVEN -> 2 }
            val fd = NativeSerial.nativeOpen(path, params.baud, params.dataBits, parity, params.stopBits)
            val status = if (rs485Ioctl) {
                try {
                    NativeSerial.nativeEnableRs485(fd)
                } catch (e: IOException) {
                    NativeSerial.nativeClose(fd)
                    throw e
                }
            } else null
            return UartTransport(path, fd, params.baud, status)
        }
    }
}
