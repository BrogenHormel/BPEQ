package app.bpeq.core.io

import app.bpeq.core.model.Profile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The whole library in one JSON file. */
@Serializable
data class LibraryBackup(
    val format: String = FORMAT,
    val version: Int = 1,
    val exportedAt: Long = System.currentTimeMillis(),
    val profiles: List<Profile>,
) {
    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        const val FORMAT = "bpeq-library"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; prettyPrint = false }

        fun fromJson(text: String): LibraryBackup {
            val b = runCatching { json.decodeFromString(serializer(), text) }
                .getOrElse { throw ImportException("Not a BPEQ library backup: ${it.message?.lineSequence()?.firstOrNull()}") }
            if (b.format != FORMAT) throw ImportException("Not a BPEQ library backup (format \"${b.format}\")")
            return b
        }

        /**
         * Merges [incoming] into [existing]: new profiles are added as they are; a profile whose id already exists is
         * skipped when identical, otherwise added as a copy with a new id and a distinct name. Nothing is overwritten.
         */
        fun merge(existing: List<Profile>, incoming: List<Profile>): List<Profile> {
            val ids = existing.map { it.id }.toMutableSet()
            val names = existing.map { it.name.lowercase() }.toMutableSet()
            val out = ArrayList<Profile>()
            for (p in incoming) {
                val same = existing.firstOrNull { it.id == p.id }
                if (same != null && same.saved.sameSettings(p.saved) && same.name == p.name) continue
                var q = if (p.id in ids) p.copy(id = java.util.UUID.randomUUID().toString()) else p
                if (q.name.lowercase() in names) {
                    var i = 2
                    while ("${q.name} ($i)".lowercase() in names) i++
                    q = q.copy(name = "${q.name} ($i)")
                }
                ids += q.id; names += q.name.lowercase(); out += q
            }
            return out
        }
    }
}
