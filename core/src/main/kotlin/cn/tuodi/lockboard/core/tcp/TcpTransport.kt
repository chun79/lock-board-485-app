package cn.tuodi.lockboard.core.tcp

import cn.tuodi.lockboard.core.client.ByteTransport
import cn.tuodi.lockboard.core.client.TransportKind
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Transparent TCP ↔ RS485 gateway (serial device server). The gateway itself may still buffer
 * serial data that [drainInput] cannot see; the client's late-reply guard covers that case.
 */
class TcpTransport private constructor(private val socket: Socket, private val host: String, private val port: Int) : ByteTransport {
    override val kind = TransportKind.TCP
    override val description get() = "TCP $host:$port"
    private val input: InputStream = socket.getInputStream()
    private val output: OutputStream = socket.getOutputStream()

    override fun write(bytes: ByteArray) {
        output.write(bytes)
        output.flush()
    }

    override fun read(buffer: ByteArray, timeoutMs: Int): Int {
        socket.soTimeout = timeoutMs.coerceAtLeast(1)
        return try {
            val n = input.read(buffer)
            if (n < 0) throw IOException("对端已关闭连接")
            n
        } catch (_: SocketTimeoutException) {
            0
        }
    }

    /**
     * Drains everything already received, up to [MAX_DRAIN_BYTES]. A backlog beyond that is not carried
     * into the next transaction (it could contain an old frame that matches the new request): it fails.
     */
    override fun drainInput(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(256)
        while (input.available() > 0) {
            val room = MAX_DRAIN_BYTES - out.size()
            if (room <= 0) throw IOException("网关积压数据超过 $MAX_DRAIN_BYTES 字节，总线未清空")
            val n = input.read(buf, 0, minOf(buf.size, input.available(), room))
            if (n <= 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    override fun close() = socket.close()

    companion object {
        private const val MAX_DRAIN_BYTES = 65_536

        @Throws(IOException::class)
        fun connect(host: String, port: Int, connectTimeoutMs: Int = 3000): TcpTransport {
            require(host.isNotBlank()) { "主机地址为空" }
            require(port in 1..65535) { "端口必须为 1..65535" }
            val socket = Socket()
            try {
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            } catch (e: IOException) {
                socket.close()
                throw e
            }
            return TcpTransport(socket, host, port)
        }
    }
}
