@file:OptIn(ExperimentalForeignApi::class)

package app.snapsync.protection

import app.snapsync.model.MemoryFootprint
import app.snapsync.model.Never
import app.snapsync.model.NeverAnswers
import app.snapsync.ports.ProcessInfo
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
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
 * The iOS [ProcessInfo] of the app process: its memory footprint. App-only: the extension composes no [ProcessInfo].
 * `ProcessInfoContract` runs live on the simulator app.
 *
 * THE FOOTPRINT is the kernel's own accounting of this task (`task_info(TASK_VM_INFO)`): `phys_footprint` is the
 * figure the system's memory-pressure exits are decided on, `ledger_phys_footprint_peak` the highest it has been, and
 * `limit_bytes_remaining` how far the process is from its own limit. Each field belongs to a revision of the struct,
 * and the kernel reports how much of it it filled, so a field it did not fill reads `null` rather than zero. A mach
 * call on our own task — no UIKit, any thread, nothing that blocks. It always answers: every iOS this app supports fills
 * the first revision for its own task, so a refusal is a broken platform, raised as an error, never a quiet "nothing
 * read" — an answer no device can give and no test could prove.
 */
@NeverAnswers(
    Never(member = "memoryFootprint", answer = "null"),
)
class IosProcessInfo : ProcessInfo {

    override fun memoryFootprint(): MemoryFootprint = memScoped {
        val info = alloc<task_vm_info>()
        val count = alloc<UIntVar>().apply { value = TASK_VM_INFO_COUNT }
        val result = task_info(mach_task_self_, TASK_VM_INFO.toUInt(), info.ptr.reinterpret<IntVar>(), count.ptr)
        val filled = count.value
        check(result == KERN_SUCCESS && filled >= TASK_VM_INFO_REV1_COUNT) {
            "task_info(TASK_VM_INFO) on our own task answered $result, filling $filled of $TASK_VM_INFO_REV1_COUNT fields"
        }
        MemoryFootprint(
            footprintBytes = info.phys_footprint.toLong(),
            peakBytes = info.ledger_phys_footprint_peak.takeIf { filled >= TASK_VM_INFO_REV3_COUNT && it > 0 },
            headroomBytes = info.limit_bytes_remaining.toLong().takeIf { filled >= TASK_VM_INFO_REV4_COUNT && it > 0 },
        )
    }
}
