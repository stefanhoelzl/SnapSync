package app.snapsync.mock

import app.snapsync.model.DiagnosticKeys
import app.snapsync.model.Fact
import app.snapsync.model.StandbyBucket
import app.snapsync.model.Thermal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The device-condition texts the mock's persisted state and the rig's lever share. */
class DeviceConditionsTextTest {

    @Test
    fun `every field round-trips — unsupported and failed included`() {
        val reading = DeviceConditionsMock.TYPICAL.copy(
            standbyBucket = Fact.Known(StandbyBucket.RARE),
            thermal = Fact.Failed("thermal service: gone"),
        )
        assertEquals(reading, DeviceConditionsText.apply(DeviceConditionsMock.TYPICAL, DeviceConditionsText.encode(reading)))
    }

    @Test
    fun `a field not named keeps its value`() {
        val applied = DeviceConditionsText.apply(DeviceConditionsMock.TYPICAL, mapOf(DiagnosticKeys.THERMAL to "serious"))
        assertEquals(DeviceConditionsMock.TYPICAL.copy(thermal = Fact.Known(Thermal.SERIOUS)), applied)
    }

    @Test
    fun `a value that does not parse is refused naming its field`() {
        val error = assertFailsWith<IllegalArgumentException> {
            DeviceConditionsText.apply(DeviceConditionsMock.TYPICAL, mapOf(DiagnosticKeys.BATTERY_PERCENT to "140"))
        }
        assertEquals(true, error.message?.startsWith(DiagnosticKeys.BATTERY_PERCENT))
    }
}
