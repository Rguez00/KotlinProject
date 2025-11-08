package org.example.project.data.perf

object Perf {
    var enabled: Boolean = true

    inline fun <T> time(label: String, block: () -> T): T {
        if (!enabled) return block()
        val t0 = System.nanoTime()
        val r = block()
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("[perf] $label: ${ms}ms")
        return r
    }

    fun size(label: String, any: Any?) {
        if (!enabled) return
        val s = when (any) {
            is Collection<*> -> any.size
            is Map<*, *>     -> any.size
            is Array<*>      -> any.size
            null             -> 0
            else             -> -1
        }
        println("[perf] size $label = $s")
    }

    fun mark(label: String) {
        if (enabled) println("[perf] ---- $label ----")
    }
}
