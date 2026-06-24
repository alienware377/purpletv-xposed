package tv.purple.xp

import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

/**
 * Obfuscation-resolution layer (task #8).
 *
 * R8 full-mode renames classes/methods every Twitch release, so the obfuscated
 * anchors below are pinned to a known-good version and MUST be re-fingerprinted
 * when bumping. The readable `tv.twitch.android.shared.chat.pub.messages.data.*`
 * model classes are R8-kept and stable across releases — those are referenced by
 * their real names and are the load-bearing part of the design.
 *
 * Verified chain on Twitch 28.6.1:
 *   kn5.a(String, List): List<MessageToken>     // tokenizer  -> inject here
 *     -> ao5.a(...)                             // MessageToken -> khm UI model
 *       -> ckm.j(..., khm, ...): SpannableString// renders the emote span
 *         -> az6.y(az6, Context, String, float): gv0   // builds CDN url -> redirect here
 *           gv0.a:String  = the emote image URL
 */
object Names {
    // --- pinned obfuscated anchors for the LIVE chat render path (Twitch 28.6.1) ---
    // Live chain (proven on device): ik5.b -> ckm.g -> ckm.j -> az6.y.
    // ik5.b returns the per-chat-line model `hn5`; field .e is the message body.
    //   hn5.a=timestamp  hn5.c="username: "  hn5.e=body  hn5.f=bool
    const val ASSEMBLER = "ik5"           // ik5.b(...) -> hn5 (chat line model)
    const val ASSEMBLER_METHOD = "b"
    const val BODY_FIELD = "e"            // hn5.e : CharSequence = the message body

    // --- (legacy/unused) stable readable model classes ---
    const val PKG = "tv.twitch.android.shared.chat.pub.messages.data"
    const val TEXT_TOKEN = "$PKG.MessageToken\$TextToken"
    const val EMOTICON_TOKEN = "$PKG.MessageToken\$EmoticonToken"

    /** Sentinel marking an id we injected; az6.y hook redirects these to 7TV/BTTV/FFZ CDN. */
    const val SENTINEL = "PTV"

    fun emoticonId(imageUrl: String) = SENTINEL + imageUrl
    fun isOurs(id: String?) = id != null && id.startsWith(SENTINEL)
    fun urlOf(id: String) = id.substring(SENTINEL.length)

    /** Resolve an anchor class, logging clearly if the rename pin is stale. */
    fun cls(lp: LoadPackageParam, name: String): Class<*>? = try {
        lp.classLoader.loadClass(name)
    } catch (t: Throwable) {
        log("MISSING anchor class '$name' — obfuscation pin is stale, re-fingerprint needed")
        null
    }
}
