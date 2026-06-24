package tv.purple.xp

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.ref.WeakReference

/**
 * Favorites section inside Twitch's NATIVE emote picker (per request: "a favorites section in
 * the emotes picker for only twitch and sub emotes that persists between chats").
 *
 * Obfuscation-immune anchors (resource ENTRY names survive R8; the resource table package is
 * still "tv.twitch.android.app" even after the app package rename, so getResourceEntryName is
 * used rather than getIdentifier):
 *   - emote_palette  = the picker's android.widget.GridView of emote cells
 *   - emote_picker   = the FrameLayout container we inject a favorites strip into
 *
 * We hook the framework GridView constructor (GridView is rare in this app, so this is cheap),
 * and when the inflated GridView turns out to be "emote_palette" we add a pinned horizontal
 * favorites strip at the TOP of the "emote_picker" container and pad the grid down so nothing is
 * hidden behind it. Tapping a favorite inserts it into the chat input (insert only, never sends —
 * same as the native picker). Long-pressing a favorite removes it. Favorites come from
 * [Favorites] (Twitch/sub only) and persist across channels via SharedPreferences.
 */
object PickerFavorites {

    /** emote_picker containers we've already injected into. */
    private val INJECTED = java.util.Collections.newSetFromMap(
        java.util.WeakHashMap<ViewGroup, Boolean>()
    )


    /** The currently-attached favorites chip row + its context, for live refresh on change. */
    @Volatile private var curRow: WeakReference<LinearLayout>? = null
    @Volatile private var curCtx: WeakReference<Context>? = null
    @Volatile private var listenerWired = false

    fun install(lp: LoadPackageParam) {
        // The emote_palette / emote_picker views are NOT real android.widget.GridView instances
        // at runtime — uiautomator only reports "android.widget.GridView" because the custom view
        // reports it as its accessibility class name. Hooking framework GridView (ctor/onMeasure/
        // onAttachedToWindow) therefore never fires. Instead we anchor class-agnostically: a single
        // ViewTreeObserver.OnGlobalLayoutListener on the chat-input window root (registered from
        // EmoteAutocomplete.attach via attachRoot) scans for the "emote_picker" container by
        // resource ENTRY name whenever the layout changes (which includes the picker expanding),
        // and injects the favorites strip the first time it appears.
        if (!listenerWired) {
            listenerWired = true
            Favorites.addListener {
                val row = curRow?.get() ?: return@addListener
                row.post { runCatching { rebuild(row) } }
            }
        }
        log("PF favorites picker hook installed (global-layout anchor)")
    }

    /** Roots we've already attached a global-layout listener to (one per chat window). */
    private val ROOTED = java.util.Collections.newSetFromMap(
        java.util.WeakHashMap<View, Boolean>()
    )

    /** Cached emote_picker container, so the layout listener is near-free once injected. */
    @Volatile private var lastContainer: WeakReference<ViewGroup>? = null

    /**
     * Register a global-layout listener on the chat-input window root. Called by
     * [EmoteAutocomplete.attach] with the chat input. On each layout pass, if the favorites strip
     * isn't already injected into the live emote_picker, scan the tree for it and inject.
     */
    fun attachRoot(input: View) {
        val root = runCatching { input.rootView }.getOrNull() ?: return
        if (!ROOTED.add(root)) return
        runCatching {
            root.viewTreeObserver.addOnGlobalLayoutListener {
                runCatching {
                    val cached = lastContainer?.get()
                    if (cached != null && cached.isAttachedToWindow && INJECTED.contains(cached)) return@addOnGlobalLayoutListener
                    val container = findByEntry(root, "emote_picker") as? ViewGroup ?: return@addOnGlobalLayoutListener
                    lastContainer = WeakReference(container)
                    onPaletteReady(container)
                }.onFailure { log("PF layout scan error: $it") }
            }
            log("PF global-layout listener attached to chat root")
        }.onFailure { log("PF attachRoot error: $it") }
    }

    /** Depth-first search for the first descendant whose resource entry name == [entry]. */
    private fun findByEntry(root: View, entry: String): View? {
        val e = runCatching { root.resources.getResourceEntryName(root.id) }.getOrNull()
        if (e == entry) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findByEntry(root.getChildAt(i) ?: continue, entry)?.let { return it }
            }
        }
        return null
    }

    private fun onPaletteReady(container: ViewGroup) {
        if (!INJECTED.add(container)) { // already injected; just refresh contents
            curRow?.get()?.let { rebuild(it) }
            return
        }
        val ctx = container.context ?: return
        val stripH = dp(ctx, 50f)

        // Build: [ ★ Favorites ][ scrollable chip row ]
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val scroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
        val star = TextView(ctx).apply {
            text = "★"
            setTextColor(Color.parseColor("#FFD24A"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(dp(ctx, 10f), 0, dp(ctx, 8f), 0)
        }
        val strip = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#FF18141D"))
            addView(star, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT
            ).apply { gravity = Gravity.CENTER_VERTICAL })
            addView(scroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }

        // Pin the strip to the TOP of the picker; push the grid down so it isn't hidden behind it.
        container.addView(strip, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, stripH, Gravity.TOP
        ))
        // Pad the scrollable emote grid (emote_palette) down by the strip height so its first row
        // isn't hidden behind the strip. Found by entry name — it's not a real GridView at runtime.
        val palette = findByEntry(container, "emote_palette")
        runCatching {
            (palette as? ViewGroup)?.clipToPadding = true
            palette?.setPadding(
                palette.paddingLeft, palette.paddingTop + stripH,
                palette.paddingRight, palette.paddingBottom
            )
        }

        curRow = WeakReference(row)
        curCtx = WeakReference(ctx)
        rebuild(row)

        // Refresh on every reopen (the picker view may be reused while favorites changed).
        palette?.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                curRow?.get()?.let { rebuild(it) }
            }
            override fun onViewDetachedFromWindow(v: View) {}
        })
        log("PF favorites strip injected into emote_picker")
    }

    /** Repopulate the chip row from the persisted favorites list. */
    private fun rebuild(row: LinearLayout) {
        val ctx = row.context ?: return
        row.removeAllViews()
        val favs = Favorites.list()
        if (favs.isEmpty()) {
            row.addView(TextView(ctx).apply {
                text = "Long-press a Twitch emote to add it here"
                setTextColor(Color.parseColor("#99FFFFFF"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                gravity = Gravity.CENTER_VERTICAL
            })
            return
        }
        val sizePx = dp(ctx, 34f)
        for (name in favs) {
            val d = favDrawable(name)
            val cell: View = if (d != null) {
                ImageView(ctx).apply {
                    setImageDrawable(d)
                    if (d is AnimatedImageDrawable) runCatching { d.start() }
                    layoutParams = LinearLayout.LayoutParams(sizePx, sizePx)
                }
            } else {
                // No cached image (e.g. emote not in the current channel's set) — show the name.
                TextView(ctx).apply {
                    text = name
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    gravity = Gravity.CENTER_VERTICAL
                }
            }
            val holder = FrameLayout(ctx).apply {
                setPadding(dp(ctx, 6f), 0, dp(ctx, 6f), 0)
                isClickable = true
                addView(cell)
                setOnClickListener {
                    if (!EmoteAutocomplete.insertEmote(name))
                        toast(ctx, "Tap the chat box first")
                }
                setOnLongClickListener {
                    Favorites.remove(name)
                    toast(ctx, "Removed $name")
                    true
                }
            }
            row.addView(holder)
        }
    }

    /** A drawable for a favorite emote: fresh animated copy if animated, else the cached static
     *  suggestion drawable (a fresh copy so it isn't shared across views), else null. */
    private fun favDrawable(name: String): Drawable? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            EmoteRepo.animBytes.containsKey(name)) {
            EmoteRepo.makeAnimated(name)?.let { return it }
        }
        EmoteRepo.suggestDrawables[name]?.let { return it.constantState?.newDrawable()?.mutate() ?: it }
        return null
    }

    private fun dp(ctx: Context, v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()
}
