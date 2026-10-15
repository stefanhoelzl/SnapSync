package app.snapsync.ports

import app.snapsync.model.MemoryFootprint

/**
 * What the operating system says about this process right now — ONE external system,
 * the OS's view of the running process.
 *
 * [memoryFootprint]: what the platform charges this process for in memory right now. The app records it as it
 * settles in the background, for the process-metric report that later says a suspended app was ended for memory.
 * `null` where nothing is answered.
 */
interface ProcessInfo : Port {
    /** This process's own memory accounting, or `null` where the platform's is not read. Any thread; never blocks. */
    fun memoryFootprint(): MemoryFootprint?
}
