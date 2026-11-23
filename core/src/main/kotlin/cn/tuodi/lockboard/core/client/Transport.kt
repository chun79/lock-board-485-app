package cn.tuodi.lockboard.core.client

import java.io.Closeable
import java.io.IOException

enum class TransportKind(val label: String, val physical: Boolean) {
    SIMULATOR("模拟器", false),
    USB("USB 转 485", true),
    UART("板载串口", true),
    TCP("TCP 串口服务器", true),
}

/**
 * Blocking byte link. All calls happen on one IO thread owned by [LockBoardClient], except [close],
 * which may be called from any thread and must unblock a pending [read].
 */
interface ByteTransport : Closeable {
    val kind: TransportKind
    val description: String

    @Throws(IOException::class)
    fun write(bytes: ByteArray)

    /**
     * Waits at most [timeoutMs] for data. Returns the number of bytes read (> 0) or 0 on timeout.
     * Throws [IOException] when the link is closed or broken (EOF, device detached).
     */
    @Throws(IOException::class)
    fun read(buffer: ByteArray, timeoutMs: Int): Int

    /** Discards bytes already received and returns them for logging. */
    @Throws(IOException::class)
    fun drainInput(): ByteArray
}

/** Monotonic time source; injectable for tests. */
interface Clock {
    fun nanoTime(): Long
    fun wallMillis(): Long
    fun elapsedMs(sinceNanos: Long): Long = (nanoTime() - sinceNanos) / 1_000_000
}

object SystemClock : Clock {
    override fun nanoTime() = System.nanoTime()
    override fun wallMillis() = System.currentTimeMillis()
}
