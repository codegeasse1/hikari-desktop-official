package android.os

object SystemClock {
    fun uptimeMillis(): Long = System.nanoTime() / 1_000_000L

    fun elapsedRealtime(): Long = System.nanoTime() / 1_000_000L

    fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
