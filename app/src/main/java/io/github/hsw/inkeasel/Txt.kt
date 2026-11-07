package io.github.hsw.inkeasel

import java.io.IOException

/**
 * A user-facing text not yet put into words: a string resource [id] (an `R.string` constant) and its format
 * [args] (`String`, `Int` or a nested [Txt], resolved first). Pure logic returns it instead of a finished
 * string, so it needs no `Context` and its tests compare ids and args, not translations; the UI resolves it
 * where it is shown (`Txt.resolve(ctx)`, `Txt.text()` in Compose).
 */
data class Txt(val id: Int, val args: List<Any>) {
    /** A [Txt] with its format [args] written out: `Txt(R.string.retry)`, `Txt(R.string.frame_signal, rssi)`. */
    constructor(id: Int, vararg args: Any) : this(id, args.toList())

    /**
     * Put into words with [lookup] (resource id and format args → text): nested [Txt] args first, then this
     * one. `resolve(ctx)` passes the resources; tests pass a fake.
     */
    fun format(lookup: (id: Int, args: Array<Any>) -> String): String =
        lookup(id, args.map { if (it is Txt) it.format(lookup) else it }.toTypedArray())
}

/**
 * A failure whose message is meant for the user, as a [Txt]. An [IOException], so callers that already
 * report I/O failures handle it unchanged; [message] is only for logs.
 */
class TxtException(val txt: Txt, cause: Throwable? = null) : IOException("text ${txt.id} ${txt.args}", cause)

/**
 * This failure in words for the user: a [TxtException] says it itself; any other exception (e.g. from the
 * network library) keeps its raw message, or its class name when it has none, inside [generic] (a string
 * resource with one `%1$s`).
 */
fun Throwable.userText(generic: Int): Txt =
    if (this is TxtException) txt else Txt(generic, message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName)
