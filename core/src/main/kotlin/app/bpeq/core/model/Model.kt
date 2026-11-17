package app.bpeq.core.model

import kotlinx.serialization.Serializable

/**
 * Filter shapes the Protocol Max stores natively.
 *
 * Shelf "Q" follows the device/devicePEQ convention: it is the RBJ shelf *slope* S
 * (alpha = sin(w0)/2 * sqrt((A + 1/A)(1/S - 1) + 2)), because that is what the WalkPlay
 * firmware, devicePEQ, eq.hangout.audio and Contour use for the Q register of a shelf. Text files from
 * squig.link / AutoEQ / APO are read with their Q unchanged, like those tools do, so profiles round-trip.
 */
@Serializable
enum class FilterType(val short: String, val label: String) {
    PEAK("PK", "Peak"),
    LOW_SHELF("LS", "Low shelf"),
    HIGH_SHELF("HS", "High shelf");

    val isShelf: Boolean get() = this != PEAK
}

/** One EQ band as the user intends it (before device quantisation). */
@Serializable
data class Band(
    val type: FilterType = FilterType.PEAK,
    val freqHz: Double = 1000.0,
    val gainDb: Double = 0.0,
    val q: Double = 1.0,
    val enabled: Boolean = true,
    /** Stable id for UI keys and drag tracking; not semantically meaningful. */
    val id: Long = nextId(),
) {
    fun sameSettings(other: Band): Boolean =
        type == other.type && freqHz == other.freqHz && gainDb == other.gainDb && q == other.q && enabled == other.enabled

    companion object {
        private var counter = System.nanoTime()
        @Synchronized fun nextId(): Long = ++counter
    }
}

@Serializable
enum class PreampMode { AUTO, MANUAL }

/** The EQ content of a profile: bands plus preamp. */
@Serializable
data class Eq(
    val bands: List<Band> = emptyList(),
    val preampMode: PreampMode = PreampMode.AUTO,
    /** Used when [preampMode] is MANUAL. Whole dB on the device; stored as entered. */
    val manualPreampDb: Double = 0.0,
) {
    val activeBands: List<Band> get() = bands.filter { it.enabled }

    fun sameSettings(other: Eq): Boolean =
        preampMode == other.preampMode && manualPreampDb == other.manualPreampDb &&
            bands.size == other.bands.size && bands.indices.all { bands[it].sameSettings(other.bands[it]) }

    companion object {
        val FLAT = Eq()
    }
}
