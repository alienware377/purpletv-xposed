package tv.purple.xp

import android.app.Activity
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.ref.WeakReference

/**
 * Landscape theater chat-panel transparency (task #10).
 *
 * Obfuscation-immune: we never name a Twitch class. We hook the framework
 * android.app.Activity lifecycle (onResume / onConfigurationChanged) and, when the device
 * is in LANDSCAPE, walk the decor view to find the chat side-panel by RESOURCE ENTRY NAME
 * (package-independent — see EmoteAutocomplete for the same trick) and set View.alpha to the
 * user's chosen opacity. In PORTRAIT we restore full opacity so the normal layout is unchanged.
 *
 * Applied on a short post-delay because the theater chat panel is inflated asynchronously
 * after the player goes fullscreen. [reapply] lets the settings slider update live.
 */
object ChatTransparency {

    /** Chat-panel container resource entry names, OUTERMOST first. In landscape theater the chat
     *  is nested chat_wrapper > chat_view_container > chat_view_delegate_root (all same bounds).
     *  The dark panel BACKGROUND drawable lives on an outer container, NOT on delegate_root — so
     *  dimming delegate_root alone faded only the text. We dim the OUTERMOST present container so
     *  the whole panel (background + text) composites once at the chosen alpha, and reset the
     *  inner ones to 1f to avoid compounding the alpha on nested views. */
    private val PANEL_IDS = listOf(
        "chat_wrapper",
        "chat_view_container",
        "chat_view_delegate_root"
    )

    /** One-shot diagnostic: log which chat views carry a background drawable. */
    @Volatile private var bgLogged = false

    @Volatile private var lastActivity: WeakReference<Activity>? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    fun install(lp: LoadPackageParam) {
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val act = param.thisObject as? Activity ?: return
                lastActivity = WeakReference(act)
                scheduleApply(act)
            }
        }
        runCatching {
            XposedBridge.hookAllMethods(Activity::class.java, "onResume", hook)
            XposedBridge.hookAllMethods(Activity::class.java, "onConfigurationChanged", hook)
            log("ChatTransparency installed (Activity onResume/onConfigurationChanged)")
        }.onFailure { log("ChatTransparency hook failed: $it") }
    }

    /** Re-apply to the last-known foreground activity (called when the slider changes). */
    fun reapply() {
        val act = lastActivity?.get() ?: return
        scheduleApply(act)
    }

    /** Activities we've attached a global-layout listener to (reapply on every layout). */
    private val GL_WIRED = java.util.Collections.newSetFromMap(
        java.util.WeakHashMap<Activity, Boolean>()
    )

    private fun scheduleApply(act: Activity) {
        // The chat fragment inflates well AFTER onResume (network-gated), and Twitch re-renders
        // can reset alpha. So besides a few timed retries, attach a global-layout listener that
        // re-applies on every layout pass (cheap: a guarded tree walk) for the activity's life.
        for (delay in longArrayOf(0L, 250L, 750L, 1500L, 3000L)) {
            handler.postDelayed({ runCatching { apply(act) }.onFailure { log("CT apply: $it") } }, delay)
        }
        if (GL_WIRED.add(act)) {
            runCatching {
                val root = act.window?.decorView ?: return
                root.viewTreeObserver.addOnGlobalLayoutListener {
                    runCatching { apply(act) }
                }
            }
        }
    }

    private fun apply(act: Activity) {
        val landscape = act.resources.configuration.orientation ==
            Configuration.ORIENTATION_LANDSCAPE
        val opacitySetting = Settings.getInt(Settings.KEY_CHAT_OPACITY, Settings.CHAT_OPACITY_DEFAULT)
        // Landscape theater: apply the user's opacity. Portrait: always full opacity (restore).
        val alpha = if (landscape) (opacitySetting.coerceIn(0, 100)) / 100f else 1f
        val root = act.window?.decorView ?: return

        // Collect every matching container by name (PANEL_IDS is already ordered outermost-first).
        val matches = HashMap<String, View>()
        walk(root) { v ->
            val entry = runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull()
            if (entry != null && entry in PANEL_IDS) matches[entry] = v
        }
        if (matches.isEmpty()) return

        // One-shot: report which chat container holds the panel background drawable.
        if (landscape && !bgLogged) {
            bgLogged = true
            for (name in PANEL_IDS) matches[name]?.let { v ->
                log("CT bg-probe '$name': background=${v.background?.javaClass?.simpleName ?: "null"} alpha=${v.alpha}")
            }
        }

        // Dim the OUTERMOST present container (first in PANEL_IDS order); reset inner ones to 1f
        // so alpha isn't multiplied across the nested chain.
        var dimmed = false
        for (name in PANEL_IDS) {
            val v = matches[name] ?: continue
            val target = if (!dimmed) { dimmed = true; alpha } else 1f
            if (v.alpha != target) v.alpha = target
        }
    }

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
