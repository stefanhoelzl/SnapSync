@file:OptIn(ExperimentalForeignApi::class)

package app.snapsync.protection

import app.snapsync.model.Availability
import app.snapsync.model.MemoryFootprint
import app.snapsync.ports.ProcessInfo
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.UIKit.UIApplication
import platform.darwin.KERN_SUCCESS
import platform.darwin.TASK_VM_INFO
import platform.darwin.TASK_VM_INFO_COUNT
import platform.darwin.TASK_VM_INFO_REV1_COUNT
import platform.darwin.TASK_VM_INFO_REV3_COUNT
import platform.darwin.TASK_VM_INFO_REV4_COUNT
import platform.darwin.mach_task_self_
import platform.darwin.task_info
import platform.darwin.task_vm_info

/**
 * The iOS [ProcessInfo] of the app process: `UIApplication.isProtectedDataAvailable`. App-only, because
 * `UIApplication` is unavailable to app extensions — the extension records the status of each protected read it
 * makes instead (capability `sync-status`, "Background entry points record protected-data state").
 *
 * `UIApplication` is main-thread-only and the entry points that ask run on the composition lane (law "Dispatcher
 * lanes are fixed by the composition"), so the read names the main lane itself. It is a property read, not work:
 * nothing blocking follows it onto main.
 *
 * WHAT IS CONTRACTED, AND WHAT CANNOT BE. `ProcessInfoContract` runs live on the simulator app — the one CI host
 * with a `UIApplication` — and holds that an unlocked device reads available, through the main-lane hop above. The
 * unavailable answer has no clause and no host: no host lets a binding enter "not unlocked since boot", because the
 * simulator implements no data protection and the rig drives only a running, unlocked app (`docs/architecture.md`,
 * "Hosts are a closed set of what changes reachable states"). That belief — `isProtectedDataAvailable` is false on
 * a device locked since boot — is Apple's documented contract, and the background entry points' device-log lines
 * are the only place it is ever observed.
 *
 * THE FOOTPRINT is the kernel's own accounting of this task (`task_info(TASK_VM_INFO)`): `phys_footprint` is the
 * figure the system's memory-pressure exits are decided on, `ledger_phys_footprint_peak` the highest it has been, and
 * `limit_bytes_remaining` how far the process is from its own limit. Each field belongs to a revision of the struct,
 * and the kernel reports how much of it it filled, so a field it did not fill reads `null` rather than zero. A mach
 * call on our own task — no UIKit, any thread, nothing that blocks.
 */
class IosProcessInfo : ProcessInfo {
    override suspend fun protectedDataAvailable(): Availability =
        if (withContext(Dispatchers.Main) { UIApplication.sharedApplication.isProtectedDataAvailable() }) {
            Availability.AVAILABLE
        } else {
            Availability.UNAVAILABLE
        }

    override fun memoryFootprint(): MemoryFootprint? = memScoped {
        val info = alloc<task_vm_info>()
        val count = alloc<UIntVar>().apply { value = TASK_VM_INFO_COUNT }
        val result = task_info(mach_task_self_, TASK_VM_INFO.toUInt(), info.ptr.reinterpret<IntVar>(), count.ptr)
        val filled = count.value
        if (result != KERN_SUCCESS || filled < TASK_VM_INFO_REV1_COUNT) return@memScoped null
        MemoryFootprint(
            footprintBytes = info.phys_footprint.toLong(),
            peakBytes = info.ledger_phys_footprint_peak.takeIf { filled >= TASK_VM_INFO_REV3_COUNT && it > 0 },
            headroomBytes = info.limit_bytes_remaining.toLong().takeIf { filled >= TASK_VM_INFO_REV4_COUNT && it > 0 },
        )
    }
}
