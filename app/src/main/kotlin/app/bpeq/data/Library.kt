package app.bpeq.data

import android.content.Context
import app.bpeq.core.model.Eq
import app.bpeq.core.model.Profile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * The profile library: one JSON file per profile (saved version, working copy and undo history together),
 * written atomically and debounced so dragging never blocks on disk.
 */
class Library(context: Context) {
    private val dir = File(context.filesDir, "profiles").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = HashMap<String, Job>()

    private val _profiles = MutableStateFlow(load())
    val profiles: StateFlow<List<Profile>> = _profiles.asStateFlow()

    private val _currentId = MutableStateFlow(prefs.getString(KEY_CURRENT, null))
    val currentId: StateFlow<String?> = _currentId.asStateFlow()

    /** Profile id + exact EQ last written to the dongle by this app (the "on device" marker). */
    private val _onDevice = MutableStateFlow(readOnDevice())
    val onDevice: StateFlow<Pair<String, Eq>?> = _onDevice.asStateFlow()

    init {
        if (_profiles.value.isEmpty()) create("My first profile", Eq.FLAT)
        if (current() == null) select(_profiles.value.first().id)
    }

    private fun load(): List<Profile> =
        dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().mapNotNull { f ->
            runCatching { Profile.fromJson(f.readText()) }.getOrNull()
        }.sortedBy { it.name.lowercase() }

    fun current(): Profile? = _currentId.value?.let { id -> _profiles.value.firstOrNull { it.id == id } }
    fun get(id: String): Profile? = _profiles.value.firstOrNull { it.id == id }

    fun select(id: String) {
        _currentId.value = id
        prefs.edit().putString(KEY_CURRENT, id).apply()
    }

    private fun put(p: Profile, debounceMs: Long = 0) {
        _profiles.value = (_profiles.value.filterNot { it.id == p.id } + p).sortedBy { it.name.lowercase() }
        pending.remove(p.id)?.cancel()
        pending[p.id] = io.launch {
            if (debounceMs > 0) delay(debounceMs)
            val tmp = File(dir, "${p.id}.json.tmp")
            tmp.writeText(p.toJson())
            tmp.renameTo(File(dir, "${p.id}.json"))
        }
    }

    /** Applies [f] to profile [id]; [debounceMs] > 0 for high-frequency updates (drags). */
    fun update(id: String, debounceMs: Long = 300, f: (Profile) -> Profile) {
        val old = get(id) ?: return
        val new = f(old)
        if (new != old) put(new, debounceMs)
    }

    fun create(name: String, eq: Eq, iem: String = ""): Profile {
        val p = Profile(name = uniqueName(name), saved = eq, iem = iem)
        put(p)
        return p
    }

    fun uniqueName(base: String): String {
        val names = _profiles.value.map { it.name.lowercase() }.toSet()
        if (base.lowercase() !in names) return base
        var i = 2
        while ("$base ($i)".lowercase() in names) i++
        return "$base ($i)"
    }

    fun rename(id: String, name: String) = update(id, 0) { it.copy(name = name.trim().ifEmpty { it.name }) }

    fun duplicate(id: String): Profile? {
        val p = get(id) ?: return null
        return create("${p.name} copy", p.working, p.iem)
    }

    /** Deletes and returns the profile so it can be restored (undo). */
    fun delete(id: String): Profile? {
        val p = get(id) ?: return null
        pending.remove(id)?.cancel()
        _profiles.value = _profiles.value.filterNot { it.id == id }
        io.launch { File(dir, "$id.json").delete() }
        if (_currentId.value == id) {
            val next = _profiles.value.firstOrNull() ?: create("My first profile", Eq.FLAT)
            select(next.id)
        }
        return p
    }

    fun restore(p: Profile) = put(p)

    fun markOnDevice(id: String, eq: Eq) {
        _onDevice.value = id to eq
        prefs.edit().putString(KEY_ON_DEVICE_ID, id).putString(KEY_ON_DEVICE_EQ, Profile(id = id, name = "", saved = eq).toJson()).apply()
    }

    fun clearOnDevice() {
        _onDevice.value = null
        prefs.edit().remove(KEY_ON_DEVICE_ID).remove(KEY_ON_DEVICE_EQ).apply()
    }

    private fun readOnDevice(): Pair<String, Eq>? {
        val id = prefs.getString(KEY_ON_DEVICE_ID, null) ?: return null
        val eq = prefs.getString(KEY_ON_DEVICE_EQ, null)?.let { runCatching { Profile.fromJson(it).saved }.getOrNull() } ?: return null
        return id to eq
    }

    companion object {
        private const val KEY_CURRENT = "current"
        private const val KEY_ON_DEVICE_ID = "on_device_id"
        private const val KEY_ON_DEVICE_EQ = "on_device_eq"
    }
}
