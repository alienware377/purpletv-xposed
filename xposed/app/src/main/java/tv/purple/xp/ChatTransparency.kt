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
 * (package-independent — see EmoteAutocomplete for the same trick) and fade its BACKGROUND to the
 * user's chosen opacity. Only the background drawable is faded, never View.alpha, so chat text and
 * emotes stay fully opaque and readable over the see-through panel.
 * In PORTRAIT we restore full opacity so the normal layout is unchanged.
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

        // Fade ONLY the dark panel background, never the chat content.
        //
        // Setting View.alpha would composite the whole subtree — background, text and emotes — at
        // the chosen opacity, which washes out the messages. Instead every matched view is held at
        // alpha 1f and the opacity is pushed into the background DRAWABLE alpha, so the panel goes
        // see-through while text and emotes stay fully opaque on top of it.
        val bgAlpha = (alpha * 255f).toInt().coerceIn(0, 255)
        for (name in PANEL_IDS) {
            val v = matches[name] ?: continue
            if (v.alpha != 1f) v.alpha = 1f   // undo whole-view dimming from earlier versions
            // mutate() so we never alter a ColorDrawable shared with other Twitch views.
            val bg = v.background?.mutate() ?: continue
            if (bg.alpha != bgAlpha) bg.alpha = bgAlpha
        }

        // Portrait passes null, which restores Twitch's own split.
        val widthPct = if (landscape) Settings.getInt(KEY_CHAT_WIDTH, CHAT_WIDTH_DEFAULT) else null
        matches["chat_wrapper"]?.let { applyChatWidth(it, widthPct) }
    }

    // -----------------------------------------------------------------------------------
    // Landscape chat panel width.
    //
    // WHY NOT layoutParams.width: in the landscape theatre layout both the chat panel and the
    // player pane are declared layout_width="0dp" (MATCH_CONSTRAINT) inside a ConstraintLayout:
    //
    //   player_pane    : constraintEnd_toStartOf = chat_guideline
    //   chat_wrapper   : constraintStart_toEndOf = chat_guideline, constraintEnd_toEndOf = parent
    //   chat_guideline : vertical Guideline with layout_constraintGuide_percent
    //
    // With 0dp the solver IGNORES the stored width and derives it from those anchors, so the two
    // panes are complementary by construction and meet at the guideline. Writing a width switched
    // the panel to a fixed-width solve anchored at its START edge (the guideline), which is why it
    // grew rightwards off the screen instead of moving left — chat text got clipped mid-word.
    //
    // The one real lever is the guideline percent. Moving it resizes the player pane, the chat
    // panel and the several other siblings pinned to the same guideline in a single layout pass.
    // guidePercent is the fraction LEFT of the guideline, so a chat width of pct maps to
    // 1 - pct/100. That also holds under RTL: the guideline mirrors, and the panel (anchored
    // start_toEnd) then occupies the left band — still (1 - p) wide either way.
    //
    // androidx is touched by REFLECTION ONLY, never named at compile time. The layout XML inflates
    // ConstraintLayout and Guideline by fully-qualified string, so R8 cannot rename them without
    // rewriting that XML. Every access is still runCatching-guarded, so a future obfuscated or
    // Compose-based build degrades to a clean no-op rather than breaking chat.
    // -----------------------------------------------------------------------------------

    const val KEY_CHAT_WIDTH = "landscape_chat_size_v3"
    const val CHAT_WIDTH_DEFAULT = 30
    private const val CHAT_WIDTH_MIN = 10
    private const val CHAT_WIDTH_MAX = 50

    /** Twitch's own guidePercent, captured before our first write so "off" restores the exact
     *  shipped split rather than a hard-coded guess. Weak-keyed: never pins a dead Activity. */
    private val originalGuidePercent = java.util.WeakHashMap<View, Float>()

    @Volatile private var fGuidePercent: java.lang.reflect.Field? = null
    @Volatile private var fStartToEnd: java.lang.reflect.Field? = null
    @Volatile private var structureLogged = false

    /** Find a declared field by name anywhere up the class hierarchy, made accessible. */
    private fun fieldOf(obj: Any, name: String): java.lang.reflect.Field? = runCatching {
        var c: Class<*>? = obj.javaClass
        while (c != null) {
            c.declaredFields.firstOrNull { it.name == name }?.let { it.isAccessible = true; return it }
            c = c.superclass
        }
        null
    }.getOrNull()

    /**
     * Resize the landscape theatre chat panel to [pct] percent of screen width.
     *
     * @param panel the view matched by resource entry name "chat_wrapper"
     * @param pct   10..50, or null to RESTORE Twitch's original split (portrait / feature off)
     *
     * Returns cleanly with the layout untouched if the expected ConstraintLayout + chat_guideline
     * structure isn't present.
     */
    private fun applyChatWidth(panel: View, pct: Int?) {
        runCatching {
            // Parent must hold a direct child named chat_guideline. Only a ConstraintLayout has a
            // Guideline child, so this subsumes a parent class check without naming a class.
            val parent = panel.parent as? ViewGroup ?: return
            var guide: View? = null
            for (i in 0 until parent.childCount) {
                val c = parent.getChildAt(i)
                // A Guideline sets itself GONE in its constructor — don't skip GONE views here.
                val e = runCatching { c.resources.getResourceEntryName(c.id) }.getOrNull()
                if (e == "chat_guideline") { guide = c; break }
            }
            val guideline = guide ?: return

            val panelLp = panel.layoutParams ?: return
            val guideLp = guideline.layoutParams ?: return

            // The panel's startToEnd must BE the guideline. This is the landscape-only signature:
            // in portrait the panel is startToStart=parent with startToEnd UNSET, so this check
            // alone stops us touching the portrait tree even if the orientation test were wrong.
            val fSte = fStartToEnd ?: fieldOf(panelLp, "startToEnd")?.also { fStartToEnd = it } ?: return
            if (fSte.getInt(panelLp) != guideline.id) return

            // Still MATCH_CONSTRAINT? If Twitch ever gives the panel a fixed width, moving the
            // guideline would no longer resize it — bail rather than half-apply.
            if (panelLp.width != 0) return

            val fGp = fGuidePercent ?: fieldOf(guideLp, "guidePercent")?.also { fGuidePercent = it } ?: return
            val current = fGp.getFloat(guideLp)

            if (!originalGuidePercent.containsKey(guideline) && current > 0f) {
                originalGuidePercent[guideline] = current
            }
            if (!structureLogged) {
                structureLogged = true
                log("CW structure ok: guidePercent=$current")
            }

            val target = if (pct == null) {
                originalGuidePercent[guideline] ?: return
            } else {
                1f - pct.coerceIn(CHAT_WIDTH_MIN, CHAT_WIDTH_MAX) / 100f
            }

            // Idempotence is load-bearing: this runs from a global-layout listener, and assigning
            // layoutParams calls requestLayout(), scheduling another pass. Only write on a real
            // change or it spins forever.
            if (kotlin.math.abs(current - target) < 0.001f) return

            fGp.setFloat(guideLp, target)
            // Framework-level assignment → requestLayout → ConstraintLayout marks its hierarchy
            // dirty and re-solves instead of reusing the cached solve.
            guideline.layoutParams = guideLp
        }.onFailure { log("CW applyChatWidth: $it") }
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
