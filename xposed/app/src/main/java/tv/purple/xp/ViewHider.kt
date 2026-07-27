package tv.purple.xp

import android.app.Activity
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

/**
 * Generic "hide a piece of Twitch's UI" engine.
 *
 * Every hide-style toggle in the settings menu reduces to the same operation: find views by
 * resource ENTRY name and set them GONE. Rather than write that hook once per feature, all the
 * rules live in [RULES] and a single layout pass applies them.
 *
 * Entry names are obfuscation-immune (R8 renames classes, not resource entries), so this keeps
 * working across Twitch updates. Views are restored to VISIBLE when their toggle is turned off,
 * so nothing needs an app restart.
 *
 * Note we only ever touch visibility — never remove views from their parent — because Twitch's
 * own presenters keep references to them and would crash on a detached view.
 */
object ViewHider {

    /**
     * @param key       SharedPreferences key backing the toggle
     * @param entries   resource entry names to hide when the toggle is on
     * @param landscape when true the rule only applies in landscape orientation
     */
    private class Rule(
        val key: String,
        val entries: List<String>,
        val landscape: Boolean = false
    )

    private val RULES = listOf(
        Rule("hide_chat_header", listOf("chat_header_container", "chat_header")),
        Rule("hide_leaderboards", listOf("leaderboards_container", "leaderboards_condensed_view")),
        Rule("disable_hype_train", listOf("hype_train", "shared_hype_train_visibility_group")),
        Rule("hide_bits_button", listOf("bits_button")),
        Rule("hide_message_input", listOf("chat_message_input_view_container",
            "message_input_view_container")),
        // Same targets as above, but only while the device is in landscape.
        Rule("auto_hide_message_input", listOf("chat_message_input_view_container",
            "message_input_view_container"), landscape = true),

        // --- Player ---
        Rule("hide_unfollow_button", listOf("unfollow_button")),
        // "Follow/Subscribe" is one setting covering both buttons in the player metadata bar.
        Rule("hide_fsb", listOf("follow_button", "subscribe_button",
            "extended_follow_button_container", "extended_subscribe_button_container")),
        Rule("hide_player_create_clip_button", listOf("create_clip_button_compose_view",
            "create_clip_text_button", "create_clip_panel")),
        Rule("hide_player_live_share_button", listOf("share_button")),
        Rule("disable_cast", listOf("cast_button")),

        // --- View ---
        Rule("hide_create_button", listOf("create_button")),
        Rule("hide_discover_feed", listOf("discovery_feed_home_root", "discovery_feed_navigation",
            "discovery_feed_pager_container"))
    )

    /** Every entry name any rule cares about, so the tree walk collects in one pass. */
    private val ALL_ENTRIES: Set<String> = RULES.flatMap { it.entries }.toSet()

    private val GL_WIRED = java.util.Collections.newSetFromMap(
        java.util.WeakHashMap<Activity, Boolean>()
    )
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var lastActivity: java.lang.ref.WeakReference<Activity>? = null

    fun install(lp: LoadPackageParam) {
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val act = param.thisObject as? Activity ?: return
                lastActivity = java.lang.ref.WeakReference(act)
                schedule(act)
            }
        }
        runCatching {
            XposedBridge.hookAllMethods(Activity::class.java, "onResume", hook)
            XposedBridge.hookAllMethods(Activity::class.java, "onConfigurationChanged", hook)
            log("ViewHider installed (${RULES.size} rules)")
        }.onFailure { log("ViewHider hook failed: $it") }
    }

    /** Re-apply immediately after a toggle changes in the settings menu. */
    fun reapply() {
        val act = lastActivity?.get() ?: return
        handler.post { runCatching { apply(act) } }
    }

    private fun schedule(act: Activity) {
        for (delay in longArrayOf(0L, 300L, 900L)) {
            handler.postDelayed({ runCatching { apply(act) }.onFailure { log("VH apply: $it") } }, delay)
        }
        if (GL_WIRED.add(act)) {
            runCatching {
                val root = act.window?.decorView ?: return
                root.viewTreeObserver.addOnGlobalLayoutListener { runCatching { apply(act) } }
            }
        }
    }

    private fun apply(act: Activity) {
        // Cheap exit: if nothing is switched on there's no reason to walk the tree at all.
        val active = RULES.filter { Settings.get(it.key, false) }
        val root = act.window?.decorView ?: return
        if (active.isEmpty() && !anyHidden) return

        val landscape = act.resources.configuration.orientation ==
            Configuration.ORIENTATION_LANDSCAPE

        // One walk, collecting every view whose entry name is referenced by any rule.
        val found = HashMap<String, MutableList<View>>()
        walk(root) { v ->
            val entry = runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull()
            if (entry != null && entry in ALL_ENTRIES) {
                found.getOrPut(entry) { mutableListOf() }.add(v)
            }
        }
        if (found.isEmpty()) return

        // An entry is hidden if ANY enabled rule naming it applies in the current orientation.
        val shouldHide = HashSet<String>()
        for (rule in active) {
            if (rule.landscape && !landscape) continue
            shouldHide.addAll(rule.entries)
        }

        var hidAny = false
        for ((entry, views) in found) {
            val hide = entry in shouldHide
            for (v in views) {
                if (hide) {
                    if (v.visibility != View.GONE) v.visibility = View.GONE
                    HIDDEN[v] = true
                    hidAny = true
                } else if (HIDDEN.remove(v) != null) {
                    // Restore ONLY views we hid ourselves. Twitch keeps plenty of these GONE on
                    // purpose — follow vs unfollow are mutually exclusive, discovery-feed
                    // containers are hidden off their tab, clip panels stay collapsed until
                    // opened. Blanket-setting every match to VISIBLE force-showed all of that and
                    // corrupted screens the rules were never meant to touch.
                    if (v.visibility != View.VISIBLE) v.visibility = View.VISIBLE
                }
            }
        }
        anyHidden = hidAny
    }

    /** Views this module set GONE, so a toggle-off restores exactly those and nothing else. */
    private val HIDDEN = java.util.WeakHashMap<View, Boolean>()

    /** Tracks whether we currently have anything hidden, so [apply] can restore after a toggle-off. */
    @Volatile private var anyHidden = false

    private inline fun walk(root: View, action: (View) -> Unit) {
        val stack = ArrayDeque<View>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val v = stack.removeLast()
            action(v)
            if (v is ViewGroup) for (i in 0 until v.childCount) stack.addLast(v.getChildAt(i))
        }
    }
}
