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
        // Entry names verified against the live 28.x view tree. The names taken from the older
        // decoded resources (create_button, discover_tab) no longer match what Twitch inflates,
        // so the current ones come first and the legacy names stay as fallbacks for other builds.
        Rule("hide_create_button", listOf("viewer_bottom_nav_create_placeholder",
            "custom_create_bottom_nav_button", "create_button")),
        // NOTE: "hide_discover_tab" is deliberately NOT a view rule -- see hideBrowseItem().
        // "hide_discover_feed" USED to live here and was actively dangerous. Its three targets:
        //   discovery_feed_home_root       root view of the ENTIRE Home fragment -- hiding it
        //                                  kills Home outright, Following tab included
        //   discovery_feed_pager_container root view of the pager fragment that IS the "Live" and
        //                                  "Clips" tabs, and parent of the error page, so hiding
        //                                  it measures the feed at 0x0 and tips it into the
        //                                  "Boo! Ghost sighting" state rather than just blanking
        //   discovery_feed_navigation      never carried by any view; a navigation-graph id only
        // Both real targets are FRAGMENT ROOTS, which the fragment manager sets back to VISIBLE on
        // every rebind, so the rule also fought the framework on every layout pass. Removing the
        // feed is now handled by hiding the individual TAB views instead -- see HomeTabs.
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
        // Later passes included deliberately: some of Twitch's chrome (notably the bottom bar)
        // only settles well after the first second, and a rule applied inside 900ms can measure a
        // layout that has not finished changing.
        for (delay in longArrayOf(0L, 300L, 900L, 2000L, 4000L, 8000L)) {
            handler.postDelayed({ runCatching { apply(act) }.onFailure { log("VH apply: $it") } }, delay)
        }
        if (GL_WIRED.add(act)) {
            runCatching {
                val root = act.window?.decorView ?: return
                root.viewTreeObserver.addOnGlobalLayoutListener { runCatching { apply(act) } }
            }
        }
    }

    const val KEY_BROWSE = "hide_discover_tab"

    /**
     * Hide the bottom bar's "Browse" entry.
     *
     * Done through the MENU, not by hiding its view. A bottom navigation bar rebuilds its item
     * views whenever the selection changes, so a view set to GONE comes back as a freshly inflated
     * VISIBLE one and only disappears again on the next layout pass -- which is exactly the
     * half-second flash of the button (or of the gap it left) when another tab is tapped.
     * The menu is the model behind those views, so an item hidden there simply never gets
     * inflated and survives every rebuild.
     *
     * Obfuscation-immune: the item id is resolved from its resource ENTRY name, and the menu is
     * reached through a public getMenu() that any menu-hosting view exposes. Views that have no
     * such method, or whose menu lacks the item, are skipped.
     */
    private fun hideBrowseItem(act: Activity, hide: Boolean) {
        val itemId = runCatching {
            act.resources.getIdentifier("viewer_bottom_nav_explore", "id", act.packageName)
        }.getOrDefault(0)
        if (itemId == 0) return
        val root = act.window?.decorView ?: return
        walk(root) { v ->
            val menu = runCatching {
                v.javaClass.getMethod("getMenu").invoke(v) as? android.view.Menu
            }.getOrNull() ?: return@walk
            val item = runCatching { menu.findItem(itemId) }.getOrNull() ?: return@walk
            // Guarded: the walk visits every menu-hosting view in the window, and some menus
            // refuse mutation. An escaping throw here used to abort the whole pass silently.
            runCatching { if (item.isVisible == hide) item.isVisible = !hide }
        }
    }

    private fun apply(act: Activity) {
        // Cheap exit: if nothing is switched on there's no reason to walk the tree at all.
        val active = RULES.filter { Settings.get(it.key, false) }
        val root = act.window?.decorView ?: return
        // Menu-backed rather than view-backed, so it runs every pass regardless of the view rules.
        runCatching { hideBrowseItem(act, Settings.get(KEY_BROWSE, false)) }

        // Report whenever the enabled set CHANGES rather than once ever. A one-shot log fired on
        // the first layout pass and then went stale, which made it report "(none)" while a rule
        // was in fact switched on -- a diagnostic that lies is worse than none at all.
        val sig = active.joinToString(",") { it.key } +
            (if (Settings.get(KEY_BROWSE, false)) ",$KEY_BROWSE" else "")
        if (sig != lastActiveSig) {
            lastActiveSig = sig
            log("VH enabled rules: " + sig.trim(',').ifEmpty { "(none)" })
        }
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
                    if (v.visibility != View.GONE) {
                        v.visibility = View.GONE
                        // Rules match by entry name ANYWHERE in the tree, with no notion of which
                        // screen they are on, so a rule written for the home feed can silently
                        // hit a dialog or a channel page. Log each distinct hide so a screen that
                        // breaks can be traced back to the exact rule that touched it.
                        val where = act.javaClass.simpleName
                        if (REPORTED.add("$entry@$where"))
                            log("VH hid '$entry' on $where (rule fired)")
                    }
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

    /** "entry@Activity" pairs already logged, so the trace stays one line per rule per screen
     *  instead of one per layout pass. */
    private val REPORTED: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Last reported set of enabled rule keys, so a change is logged exactly once. */
    @Volatile private var lastActiveSig: String? = null

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
