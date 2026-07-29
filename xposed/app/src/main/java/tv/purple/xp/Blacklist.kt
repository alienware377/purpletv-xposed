package tv.purple.xp

import android.text.SpannableStringBuilder
import android.text.Spanned
import de.robv.android.xposed.XposedHelpers

/**
 * Chat blacklist: hide messages containing a word, or sent by a given user.
 *
 * Split in two because the halves happen at very different moments. Matching runs here, on the
 * chat-line model as it is assembled; the actual row hiding runs in [BlacklistRows], against the
 * View that eventually displays it. The two are joined by a [Marker] span attached to the line's
 * body, which survives Twitch's own concatenation of the line (it appends through a
 * SpannableStringBuilder, which copies spans) and so arrives intact at TextView.setText.
 *
 * That marker is the whole design: it means the decision is made once, where the message model is
 * readable, and the View layer never needs to know what a chat message is.
 */
object Blacklist {

    const val KEY_WORDS = "blacklist_terms"

    /** Attached to a hidden line's body so [BlacklistRows] can recognise it at the View layer. */
    object Marker

    /** Match modes, mirroring the highlighter's so the two editors feel the same. */
    enum class Type { USERNAME, SENSITIVE, INSENSITIVE }

    class Term(val word: String, val type: Type)

    // ---------------------------------------------------------------- store
    //
    // Serialised one entry per line as "type|word", word last so it may contain the separator.

    /** Parsed form of the stored list. Rebuilt only on save: [matches] runs once per message per
     *  bind, inside a layout pass, so re-reading SharedPreferences there would be a real cost. */
    private class Rules(
        val users: List<String>,          // lower-cased
        val sensitive: List<String>,
        val insensitive: List<String>     // lower-cased
    ) {
        val empty = users.isEmpty() && sensitive.isEmpty() && insensitive.isEmpty()
    }

    @Volatile private var cached: Rules? = null

    fun terms(): List<Term> {
        val raw = Settings.getString(KEY_WORDS)
        if (raw.isEmpty()) return emptyList()
        val out = ArrayList<Term>()
        for (line in raw.split('\n')) {
            if (line.isBlank()) continue
            val a = line.indexOf('|')
            if (a < 0) continue
            val type = when (line.substring(0, a)) {
                "U" -> Type.USERNAME
                "S" -> Type.SENSITIVE
                else -> Type.INSENSITIVE
            }
            val word = line.substring(a + 1)
            if (word.isNotBlank()) out.add(Term(word, type))
        }
        return out
    }

    fun save(list: List<Term>) {
        Settings.setString(KEY_WORDS, list.joinToString("\n") {
            val t = when (it.type) {
                Type.USERNAME -> "U"; Type.SENSITIVE -> "S"; Type.INSENSITIVE -> "I"
            }
            "$t|${it.word}"
        })
        cached = null
        // Rows already on screen are never re-bound, so a term the user just deleted would leave
        // its messages hidden until they happened to scroll off and back.
        BlacklistRows.reapply()
    }

    fun add(t: Term) = save(terms() + t)
    fun remove(index: Int) = save(terms().filterIndexed { i, _ -> i != index })

    private fun rules(): Rules = cached ?: run {
        val all = terms()
        Rules(
            users = all.filter { it.type == Type.USERNAME }.map { it.word.lowercase() },
            sensitive = all.filter { it.type == Type.SENSITIVE }.map { it.word },
            insensitive = all.filter { it.type == Type.INSENSITIVE }.map { it.word.lowercase() }
        ).also { cached = it }
    }

    // ---------------------------------------------------------------- match

    /** Word separators. Wider than whitespace so "word." and "word!" still match, matching the
     *  behaviour of the original this feature is modelled on. */
    private val SPLIT = Regex("[\\s,.!?-]+")

    /** True when the message among [args] should be hidden. */
    fun matches(args: Array<Any?>?): Boolean {
        val r = rules()
        if (r.empty) return false
        val msg = ChatTokens.message(args) ?: return false

        if (r.users.isNotEmpty()) {
            val login = ChatIdentity.Sender.of(args)?.login?.lowercase()
            if (login != null && login in r.users) return true
        }
        if (r.sensitive.isEmpty() && r.insensitive.isEmpty()) return false

        for (text in ChatTokens.matchableOf(msg)) {
            for (w in text.split(SPLIT)) {
                if (w.isEmpty()) continue
                if (r.sensitive.isNotEmpty() && w in r.sensitive) return true
                if (r.insensitive.isNotEmpty() && w.lowercase() in r.insensitive) return true
            }
        }
        return false
    }

    /**
     * Tag an assembled chat line as hidden.
     *
     * Must run AFTER [ChatLineStyle], which replaces the body wholesale when recovering a deleted
     * message and would drop the marker with it.
     *
     * The marker rides the body, but falls back to the username slot when the body is empty — a
     * line can legitimately have none (a gigantified-emote-only message, for one), and a span over
     * an empty range is not copied when Twitch concatenates the line.
     */
    /** Lines matched so far. Read by [BlacklistRows] to bound its own diagnostic. */
    @Volatile var marked = 0
        private set

    fun mark(model: Any?) {
        model ?: return
        val ok = tag(model, Names.BODY_FIELD) || tag(model, Names.NAME_FIELD)
        if (ok && ++marked == 1) log("BL matched a message, marker attached")
    }

    private fun tag(model: Any, field: String): Boolean {
        val cs = runCatching {
            XposedHelpers.getObjectField(model, field) as? CharSequence
        }.getOrNull() ?: return false
        if (cs.isEmpty()) return false
        if (cs is Spanned && cs.getSpans(0, cs.length, Marker::class.java).isNotEmpty()) return true
        val out = SpannableStringBuilder(cs)
        out.setSpan(Marker, 0, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        XposedHelpers.setObjectField(model, field, out)
        return true
    }
}
