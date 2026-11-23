package cn.tuodi.lockboard.core.client

import cn.tuodi.lockboard.core.protocol.toHex
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

enum class LogKind(val tag: String) {
    TX("发送"),
    RX("接收"),
    RX_ECHO("回显"),
    RX_STRAY("无关帧"),
    RX_LATE("迟到"),
    RX_BAD("坏帧"),
    GARBAGE("杂散"),
    INFO("信息"),
    WARN("警告"),
    ERROR("错误"),
}

data class LogEntry(
    val seq: Long,
    val wallMillis: Long,
    val kind: LogKind,
    val bytes: List<Int>,
    val text: String,
) {
    fun format(): String = buildString {
        append(TIME_FORMAT.get()!!.format(Date(wallMillis)))
        append(" [").append(kind.tag).append("] ")
        if (bytes.isNotEmpty()) append(bytes.toHex()).append("  ")
        append(text)
    }

    private companion object {
        val TIME_FORMAT: ThreadLocal<SimpleDateFormat> =
            ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT) }
    }
}

/** Thread-safe bounded communication log with listeners (UI refresh, file sink). */
class CommLog(private val capacity: Int = 5000, private val clock: Clock = SystemClock) {
    private val entries = ArrayDeque<LogEntry>()
    private var nextSeq = 1L
    private val listeners = CopyOnWriteArrayList<(LogEntry) -> Unit>()

    fun add(kind: LogKind, text: String, bytes: List<Int> = emptyList()): LogEntry {
        val entry: LogEntry
        synchronized(this) {
            entry = LogEntry(nextSeq++, clock.wallMillis(), kind, bytes, text)
            entries.addLast(entry)
            while (entries.size > capacity) entries.removeFirst()
        }
        listeners.forEach { runCatching { it(entry) } }
        return entry
    }

    fun snapshot(): List<LogEntry> = synchronized(this) { entries.toList() }

    fun clear() = synchronized(this) { entries.clear() }

    fun addListener(listener: (LogEntry) -> Unit) = listeners.add(listener)

    fun removeListener(listener: (LogEntry) -> Unit) = listeners.remove(listener)
}
