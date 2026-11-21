package app.bpeq.core.model

import app.bpeq.core.tune.Curve
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * A profile in the library. [saved] is the last saved version; [draft] is the working copy being edited
 * (null = no unsaved changes). Undo/redo history is per profile and persisted with it.
 */
@Serializable
data class Profile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val saved: Eq = Eq.FLAT,
    val draft: Eq? = null,
    /** IEM this profile is for (feature 9); free text, empty = none. */
    val iem: String = "",
    val favorite: Boolean = false,
    // Defaults are evaluated when encoding; a value equal to "now" would be left out and re-stamped on load.
    @OptIn(ExperimentalSerializationApi::class) @EncodeDefault val createdAt: Long = System.currentTimeMillis(),
    @OptIn(ExperimentalSerializationApi::class) @EncodeDefault val updatedAt: Long = createdAt,
    val undo: List<Eq> = emptyList(),
    val redo: List<Eq> = emptyList(),
    /** Measurement + target this profile was tuned for (graph overlays), if any. */
    val tuning: Tuning? = null,
) {
    /** What the editor shows and what Send writes. */
    val working: Eq get() = draft ?: saved
    val dirty: Boolean get() = draft != null && !draft.sameSettings(saved)

    /** Applies an edit as one undo step. */
    fun edit(newEq: Eq, now: Long = System.currentTimeMillis()): Profile {
        if (newEq == working) return this
        return copy(draft = newEq, undo = (undo + working).takeLast(HISTORY), redo = emptyList(), updatedAt = now)
    }

    /** Replaces the working copy without a new undo step (used while a drag is in progress). */
    fun replaceWorking(newEq: Eq): Profile = copy(draft = newEq)

    fun undo(): Profile {
        val prev = undo.lastOrNull() ?: return this
        return copy(draft = prev, undo = undo.dropLast(1), redo = (redo + working).takeLast(HISTORY))
    }

    fun redo(): Profile {
        val next = redo.lastOrNull() ?: return this
        return copy(draft = next, redo = redo.dropLast(1), undo = (undo + working).takeLast(HISTORY))
    }

    fun save(now: Long = System.currentTimeMillis()) = copy(saved = working, draft = null, updatedAt = now)

    /** Discard the working copy (as an undoable step). */
    fun revert(): Profile = if (draft == null) this else edit(saved)

    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        const val HISTORY = 100
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        fun fromJson(s: String): Profile = json.decodeFromString(serializer(), s)
    }
}

/** The IEM measurement and target used for auto-tuning, stored compactly (resampled, normalised). */
@Serializable
data class Tuning(val measurement: Curve, val target: Curve)
