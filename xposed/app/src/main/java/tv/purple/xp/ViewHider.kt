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
            "message_input_view_container"), landscape = true)
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

        var hidTracker = false
        for ((entry, views) in found) {
            val target = if (entry in shouldHide) View.GONE else View.VISIBLE
            if (target == View.GONE) hidTracker = true
            for (v in views) if (v.visibility != target) v.visibility = target
        }
        anyHidden = hidTracker
    }

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
