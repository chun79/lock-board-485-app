package cn.tuodi.lockboard.core.client

import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Test transport: [responder] maps each written chunk to (delayMs, bytes) deliveries. */
class ScriptedTransport(
    override val kind: TransportKind = TransportKind.USB,
    private val responder: (written: List<Int>, index: Int) -> List<Pair<Long, List<Int>>>,
) : ByteTransport {
    override val description = "scripted"
    private val lock = ReentrantLock()
    private val ready = lock.newCondition()
    private val pending = ArrayList<Pair<Long, List<Int>>>()
    private val available = ArrayDeque<Int>()
    private var closed = false
    val written = ArrayList<List<Int>>()

    override fun write(bytes: ByteArray) = lock.withLock {
        if (closed) throw IOException("closed")
        val chunk = bytes.map { it.toInt() and 0xFF }
        val index = written.size
        written += chunk
        val now = System.nanoTime()
        responder(chunk, index).forEach { (delay, data) -> pending += (now + delay * 1_000_000) to data }
        pending.sortBy { it.first }
        ready.signalAll()
    }

    private fun promote(now: Long) {
        val due = pending.filter { it.first <= now }
        pending.removeAll(due)
        due.forEach { available.addAll(it.second) }
    }

    override fun read(buffer: ByteArray, timeoutMs: Int): Int {
        lock.lock()
        try {
            val deadline = System.nanoTime() + timeoutMs * 1_000_000L
            while (true) {
                if (closed) throw IOException("closed")
                val now = System.nanoTime()
                promote(now)
                if (available.isNotEmpty()) {
                    var n = 0
                    while (n < buffer.size && available.isNotEmpty()) buffer[n++] = available.removeFirst().toByte()
                    return n
                }
                if (now >= deadline) return 0
                ready.awaitNanos(minOf(deadline, pending.firstOrNull()?.first ?: deadline) - now)
            }
        } finally {
            lock.unlock()
        }
    }

    override fun drainInput(): ByteArray = lock.withLock {
        promote(System.nanoTime())
        val out = ByteArray(available.size) { available.removeFirst().toByte() }
        out
    }

    override fun close() = lock.withLock { closed = true; ready.signalAll() }
}
