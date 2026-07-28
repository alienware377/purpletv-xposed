package tv.purple.xp

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * Timestamp reformatting, `/me` action styling and deleted-message styling.
 *
 * All three need values that live on the chat MESSAGE rather than the rendered line: the real
 * message time (the rendered timestamp slot is already formatted down to minutes, so seconds can
 * never be recovered from it), and the isDeleted / isAction flags.
 *
 * Those fields are reached WITHOUT pinning any obfuscated name. The message is a Kotlin data class
 * whose generated toString() keeps the original labels verbatim through R8, and its declared-field
 * order matches that label order -- independently confirmed, since resolving `sender` purely by
 * label matching landed on exactly the field the smali shows at that position. So:
 *
 *   1. Parse timestampSeconds out of toString() ONCE and find the sole Long field holding that
 *      value. That is self-verifying -- a wrong field cannot hold the right epoch.
 *   2. The four booleans (isDeleted, isDeletedMessageClickable, isAction,
 *      isMessageEffectAnimationsEnabled) are declared immediately after it, in that order, so they
 *      are addressed as offsets from a field we just proved rather than by name or absolute index.
 *
 * If any of that fails to line up the whole object goes inert and chat renders untouched.
 */
object ChatLineStyle {

    const val KEY_TIMESTAMP = "timestamps_v2"
    const val KEY_ME_STYLE = "me_style"
    const val KEY_DELETED = "deleted_messages"

    /** Matches the "Timestamp Format" dropdown order in [PurpleMenu]. Index 0 leaves Twitch alone. */
    private val TIME_PATTERNS = arrayOf(
        "",            // Default
        "h:mm", "h:mm:ss",
        "H:mm", "H:mm:ss",
        "hh:mm", "hh:mm:ss",
        "HH:mm", "HH:mm:ss"
    )

    private const val ME_DISABLED = 0
    private const val ME_COLORED = 1
    private const val ME_ITALIC = 2
    private const val ME_ITALIC_COLORED = 3

    private const val DEL_DEFAULT = 0
    private const val DEL_MOD = 1
    private const val DEL_STRIKE = 2
    private const val DEL_GREY = 3

    private const val GREY = 0xFF6E6E78.toInt()

    /** Marks a line we've already styled -- Twitch re-assembles each line several times. */
    private object Marker

    fun apply(model: Any?, args: Array<Any?>?) {
        model ?: return
        val tsMode = Settings.getInt(KEY_TIMESTAMP, 0)
        val meMode = Settings.getInt(KEY_ME_STYLE, 0)
        val delMode = Settings.getInt(KEY_DELETED, 0)
        if (tsMode == 0 && meMode == ME_DISABLED && delMode == DEL_DEFAULT) return

        val msg = Fields.message(args) ?: return
        if (tsMode != 0) runCatching { applyTimestamp(model, msg, tsMode) }
            .onFailure { log("CLS timestamp: $it") }
        if (meMode != ME_DISABLED || delMode != DEL_DEFAULT)
            runCatching { applyBodyStyle(model, msg, meMode, delMode) }
                .onFailure { log("CLS body: $it") }
    }

    /**
     * Rewrite the timestamp slot from the message's own epoch seconds.
     *
     * Left alone when Twitch rendered no timestamp at all -- that means the user has timestamps
     * switched off in Twitch's own settings, and this option is a FORMAT choice, not a way to
     * force them back on.
     */
    private fun applyTimestamp(model: Any, msg: Any, mode: Int) {
        val pattern = TIME_PATTERNS.getOrNull(mode) ?: return
        if (pattern.isEmpty()) return
        val existing = XposedHelpers.getObjectField(model, Names.TIME_FIELD) as? CharSequence ?: return
        if (existing.isEmpty()) return
        val seconds = Fields.timestampSeconds(msg) ?: return
        val text = runCatching {
            java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault())
                .format(java.util.Date(seconds * 1000L))
        }.getOrNull() ?: return
        // Twitch's own slot carries a trailing space that separates it from the badges.
        val out = "$text "
        if (out == existing.toString()) return
        XposedHelpers.setObjectField(model, Names.TIME_FIELD, out)
    }

    /** Style the message body for `/me` actions and for deleted messages. */
    private fun applyBodyStyle(model: Any, msg: Any, meMode: Int, delMode: Int) {
        val isAction = meMode != ME_DISABLED && Fields.isAction(msg) == true
        val isDeleted = delMode != DEL_DEFAULT && Fields.isDeleted(msg) == true
        if (!isAction && !isDeleted) return

        var body = XposedHelpers.getObjectField(model, Names.BODY_FIELD) as? CharSequence ?: return
        if (body.isEmpty()) return
        if (body is Spanned && body.getSpans(0, body.length, Marker::class.java).isNotEmpty()) return

        // For a deleted message the body Twitch hands us is only a placeholder ("message
        // deleted"); the REAL rendered body -- with its native emote spans and colouring intact --
        // is parked inside the ClickableSpan that powers Twitch's own tap-to-reveal. Recover it,
        // otherwise every option below would just be styling the placeholder text.
        if (isDeleted) recoverDeleted(body)?.let { body = it }

        val out = SpannableStringBuilder(body)
        val end = out.length

        if (isAction) {
            if (meMode == ME_ITALIC || meMode == ME_ITALIC_COLORED)
                out.setSpan(StyleSpan(Typeface.ITALIC), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (meMode == ME_COLORED || meMode == ME_ITALIC_COLORED)
                // Twitch tints an action line in the sender's own chat colour; reuse it when the
                // sender has one set, and otherwise leave the default colour alone.
                Fields.senderColor(msg)?.let {
                    out.setSpan(ForegroundColorSpan(it), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
        }

        if (isDeleted) when (delMode) {
            DEL_STRIKE -> out.setSpan(StrikethroughSpan(), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            DEL_GREY -> out.setSpan(ForegroundColorSpan(GREY), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            DEL_MOD -> Unit   // recovered text, unstyled: what a moderator sees
        }

        out.setSpan(Marker, 0, out.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        XposedHelpers.setObjectField(model, Names.BODY_FIELD, out)
    }

    /**
     * Pull the original body out of the deleted-message placeholder.
     *
     * Twitch's own tap-to-reveal keeps the pre-deletion rendered text on the ClickableSpan it
     * lays over the placeholder. Located without naming anything obfuscated: ClickableSpan is a
     * framework class, and on that span the original is the single declared field whose TYPE is
     * android.text.SpannedString. Returns null -- leaving the placeholder untouched -- if the
     * shape ever changes.
     */
    private fun recoverDeleted(body: CharSequence): CharSequence? = runCatching {
        val spanned = body as? Spanned ?: return null
        val spans = spanned.getSpans(0, spanned.length, android.text.style.ClickableSpan::class.java)
        for (sp in spans) {
            for (f in sp.javaClass.declaredFields) {
                if (f.type != android.text.SpannedString::class.java) continue
                f.isAccessible = true
                val original = f.get(sp) as? CharSequence ?: continue
                if (original.isEmpty()) continue
                if (deletedLogged < 2) {
                    deletedLogged++
                    log("CLS recovered deleted body (${original.length} chars) from ${sp.javaClass.name}")
                }
                return original
            }
        }
        null
    }.getOrNull()

    @Volatile private var deletedLogged = 0

    /** Structural, one-time resolution of the message fields this file needs. */
    private object Fields {

        private const val MSG_PREFIX = "LiveChatMessage("
        private val TS = Regex("timestampSeconds=(\\d+)")

        @Volatile private var argIndex = -1
        @Volatile private var msgClass: Class<*>? = null
        @Volatile private var resolved = false
        @Volatile private var failed = false

        private var fTimestamp: Field? = null
        private var fDeleted: Field? = null
        private var fAction: Field? = null
        private var fColor: Field? = null

        fun message(args: Array<Any?>?): Any? {
            args ?: return null
            if (failed) return null
            val cached = msgClass
            if (cached != null) {
                val a = args.getOrNull(argIndex)
                if (a != null && cached.isInstance(a)) { ensure(a); return if (failed) null else a }
            }
            for (i in args.indices) {
                val a = args[i] ?: continue
                val cn = a.javaClass.name
                if (cn.startsWith("java.") || cn.startsWith("android.")) continue
                val s = runCatching { a.toString() }.getOrNull() ?: continue
                if (!s.startsWith(MSG_PREFIX)) continue
                argIndex = i; msgClass = a.javaClass
                ensure(a)
                return if (failed) null else a
            }
            return null
        }

        private fun ensure(msg: Any) {
            if (resolved || failed) return
            synchronized(this) {
                if (resolved || failed) return
                runCatching { resolve(msg) }.onFailure { log("CLS resolve: $it") }
                if (fTimestamp == null) {
                    failed = true
                    log("CLS could not locate the message timestamp — line styling disabled")
                } else {
                    resolved = true
                    log("CLS fields: ts=${fTimestamp?.name} deleted=${fDeleted?.name}" +
                        " action=${fAction?.name} color=${fColor?.name}")
                }
            }
        }

        private fun resolve(msg: Any) {
            val seconds = TS.find(msg.toString())?.groupValues?.get(1)?.toLongOrNull() ?: return
            val declared = msg.javaClass.declaredFields.filter { !Modifier.isStatic(it.modifiers) }

            // Anchor: the one field actually holding that epoch. Value-matched, so it cannot
            // silently bind to the wrong field the way a positional guess could.
            var anchor = -1
            for ((idx, f) in declared.withIndex()) {
                if (f.type != java.lang.Long::class.java && f.type != java.lang.Long.TYPE) continue
                val v = runCatching { f.isAccessible = true; f.get(msg) }.getOrNull()
                if ((v as? Long) == seconds) { anchor = idx; fTimestamp = f; break }
            }
            if (anchor < 0) return

            // isDeleted, isDeletedMessageClickable, isAction, isMessageEffectAnimationsEnabled are
            // declared immediately after it, in that order. Offsets from a proven field, never
            // absolute positions.
            fun boolAt(i: Int): Field? = declared.getOrNull(i)
                ?.takeIf { it.type == java.lang.Boolean.TYPE || it.type == java.lang.Boolean::class.java }
                ?.also { it.isAccessible = true }
            fDeleted = boolAt(anchor + 1)
            fAction = boolAt(anchor + 3)

            // senderColor is the only Integer on the message.
            fColor = declared.firstOrNull { it.type == java.lang.Integer::class.java }
                ?.also { it.isAccessible = true }
        }

        fun timestampSeconds(msg: Any): Long? =
            runCatching { fTimestamp?.get(msg) as? Long }.getOrNull()

        fun isDeleted(msg: Any): Boolean? =
            runCatching { fDeleted?.get(msg) as? Boolean }.getOrNull()

        fun isAction(msg: Any): Boolean? =
            runCatching { fAction?.get(msg) as? Boolean }.getOrNull()

        fun senderColor(msg: Any): Int? =
            runCatching { (fColor?.get(msg) as? Int)?.let { 0xFF000000.toInt() or it } }.getOrNull()
    }
}
