package cn.tuodi.lockboard.core.client

import cn.tuodi.lockboard.core.protocol.Reply
import cn.tuodi.lockboard.core.protocol.RequestInfo
import cn.tuodi.lockboard.core.protocol.toHex

enum class CommError(val label: String) {
    TIMEOUT("超时无回复"),

    /** An action was written but no reply arrived: it may or may not have been executed. */
    OUTCOME_UNKNOWN("已发送，执行结果未知"),
    PROTOCOL("回复格式异常"),
    TRANSPORT("链路错误"),
    REJECTED("安全策略拒绝"),
    NOT_CONNECTED("未连接"),
}

sealed class CommResult<out R : Reply> {
    abstract val command: RequestInfo
    abstract val attempts: Int

    data class Ok<out R : Reply>(
        override val command: RequestInfo,
        val reply: R,
        /** Raw reply frame (empty for a broadcast). */
        val raw: List<Int>,
        /** From the end of the last transmission to the complete reply. */
        val latencyMs: Long,
        override val attempts: Int,
        val notes: List<String> = emptyList(),
        /**
         * The request was written while an earlier request with the same reply shape to this board was
         * still unanswered: this reply may be that late reply (see LockBoardClient reply hygiene).
         */
        val ambiguous: Boolean = false,
        /** Monotonic time the request was written. */
        val writtenAtNanos: Long = 0,
    ) : CommResult<R>() {
        val confirmed: Boolean get() = !ambiguous
    }

    data class Failed(
        override val command: RequestInfo,
        val error: CommError,
        val message: String,
        override val attempts: Int,
        val raw: List<Int>? = null,
        /** Null when nothing was written (rejected, not connected, failed before the write). */
        val writtenAtNanos: Long? = null,
    ) : CommResult<Nothing>()

    fun okOrNull(): Ok<R>? = this as? Ok<R>

    /** Reply that can be attributed to this request, or null. */
    fun confirmedReply(): R? = (this as? Ok<R>)?.takeIf { it.confirmed }?.reply

    fun describe(): String = when (this) {
        is Ok -> buildString {
            append(command.title).append("：").append(reply.summary())
            append("（").append(latencyMs).append(" ms")
            if (attempts > 1) append("，第 ").append(attempts).append(" 次成功")
            append("）")
            if (ambiguous) append(" ⚠回复归属不明，结果未确认")
            notes.forEach { append(" ⚠").append(it) }
        }
        is Failed -> "${command.title}：${error.label}${if (message.isNotBlank()) "（$message）" else ""}" +
            (raw?.let { " [${it.toHex()}]" } ?: "")
    }
}
