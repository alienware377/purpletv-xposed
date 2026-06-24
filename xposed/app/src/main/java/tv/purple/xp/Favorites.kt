package tv.purple.xp

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Persistent favorite-emote store. Holds an ORDERED list of emote names, restricted to
 * the user's own Twitch / sub emotes (a name is only favoritable if it exists in
 * [EmoteRepo.twitch] — the harvested Twitch + personal-GQL + global Twitch set). 7TV/BTTV/FFZ
 * names are rejected, so favorites are "Twitch and sub emotes" only, per request.
 *
 * Persisted via [Settings] as a U+0001-joined string (U+0001 cannot occur in an emote token),
 * so favorites survive app restarts and are shared across every channel/chat the user opens.
 *
 * Listeners are notified on every change so an open favorites bar can live-refresh.
 */
object Favorites {
    private const val SEP = ""

    private val cache = CopyOnWriteArrayList<String>()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    @Volatile private var loaded = false

    private fun ensure() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val raw = Settings.getString(Settings.KEY_FAVORITES, "")
            if (raw.isNotEmpty()) {
                for (n in raw.split(SEP)) if (n.isNotBlank()) cache.add(n)
            }
            loaded = true
        }
    }

    /** Ordered favorite names (most-recently-added last). */
    fun list(): List<String> { ensure(); return cache.toList() }

    fun contains(name: String): Boolean { ensure(); return cache.contains(name) }

    /** Only the user's own Twitch / sub emotes may be favorited. */
    fun isFavoritable(name: String): Boolean =
        name.isNotBlank() && EmoteRepo.twitch.containsKey(name)

    /** Add if favoritable + not already present. Returns true if the list changed. */
    fun add(name: String): Boolean {
        ensure()
        if (!isFavoritable(name) || cache.contains(name)) return false
        cache.add(name); persist(); notifyChanged(); return true
    }

    fun remove(name: String): Boolean {
        ensure()
        if (!cache.remove(name)) return false
        persist(); notifyChanged(); return true
    }

    /** Toggle membership. Returns the NEW state (true = now a favorite). */
    fun toggle(name: String): Boolean {
        ensure()
        return if (cache.contains(name)) { remove(name); false } else add(name)
    }

    fun addListener(l: () -> Unit) { listeners.add(l) }

    private fun persist() = Settings.setString(Settings.KEY_FAVORITES, cache.joinToString(SEP))

    private fun notifyChanged() { for (l in listeners) runCatching { l() } }
}
