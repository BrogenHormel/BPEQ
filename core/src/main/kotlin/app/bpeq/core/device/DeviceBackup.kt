package app.bpeq.core.device

import app.bpeq.core.device.MaxProtocol.Registers
import app.bpeq.core.model.Band
import app.bpeq.core.model.FilterType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A snapshot of what the device held, for restoring later. Includes the raw coefficient bytes for the record. */
@Serializable
data class DeviceBackup(
    val timeMs: Long,
    val version: String,
    val slot: Int,
    val preampDb: Int,
    val registers: List<Registers>,
    val biquadsHex: List<String> = emptyList(),
) {
    /** A plan that puts this state back, or null with the reason if it can't be written within limits. */
    fun toPlan(): Result<WritePlan> = runCatching {
        require(registers.size == MaxLimits.BANDS) { "backup has ${registers.size} bands" }
        val regs = registers.mapIndexed { i, r ->
            // Disabled/erased slots go back as the factory flat for that slot.
            if (r.freq == 0 || r.freq == 0xFFFF || r.q256 == 0) MaxLimits.flatRegisters(i) else r
        }
        WritePlan(regs, preampDb)
    }

    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun fromJson(s: String): DeviceBackup = json.decodeFromString(serializer(), s)

        fun of(state: DeviceState, timeMs: Long = System.currentTimeMillis()) = DeviceBackup(
            timeMs, state.version, state.slot, state.preampDb, state.registers,
            state.bands.map { DeviceLog.hex(it.biquad, false) },
        )
    }
}

/** Fixed plans used by the hardware write test. All cuts, so nothing can get louder. */
object TestPlans {
    fun flat(): WritePlan = WritePlan(List(MaxLimits.BANDS) { MaxLimits.flatRegisters(it) }, 0)

    /** PK 1 kHz -6 dB Q 1, LS 100 Hz -3 dB, HS 8 kHz -3 dB, preamp -1 dB: clearly audible, every filter type, all cuts. */
    fun audibleCut(): WritePlan {
        val bands = listOf(
            Band(FilterType.PEAK, 1000.0, -6.0, 1.0),
            Band(FilterType.LOW_SHELF, 100.0, -3.0, 0.71),
            Band(FilterType.HIGH_SHELF, 8000.0, -3.0, 0.71),
        ).map(MaxLimits::quantize)
        return WritePlan(List(MaxLimits.BANDS) { bands.getOrNull(it) ?: MaxLimits.flatRegisters(it) }, -1)
    }
}
