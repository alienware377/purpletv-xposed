# PurpleTV XP — 7TV, BTTV & FFZ Emotes for the Twitch Android App

**An Xposed / LSPosed module that adds third-party emotes, emote autocomplete, a favorites bar, automatic channel-point claiming and adjustable landscape chat opacity to the official Twitch app for Android.**

If you have ever wanted BetterTTV, FrankerFaceZ and 7TV emotes to actually render in Twitch mobile chat instead of showing up as plain text, this module does that — without replacing the Twitch app and without touching your login.

[![Latest release](https://img.shields.io/github/v/release/alienware377/purpletv-xposed?label=download&style=flat-square)](https://github.com/alienware377/purpletv-xposed/releases/latest)
[![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3ddc84?style=flat-square)](#requirements)
[![Xposed](https://img.shields.io/badge/Xposed-LSPosed%20%7C%20LSPatch-9146ff?style=flat-square)](#installation)

---

## Features

| Feature | What it does |
| --- | --- |
| **7TV emotes** | Global and per-channel 7TV emotes render inline in chat, including animated WEBP. |
| **BetterTTV (BTTV) emotes** | Global and channel BTTV emotes, animated GIF support. |
| **FrankerFaceZ (FFZ) emotes** | Global and channel FFZ emote sets. |
| **Emote autocomplete** | Start typing an emote name — no leading colon required — and pick it from an inline suggestion strip. |
| **Favorites bar** | Pin your most-used Twitch and subscriber emotes to a bar inside the native emote picker. Persists across channels and restarts. |
| **Auto-claim channel points** | Bonus chests are claimed automatically in the background, whether or not the chest is on screen. |
| **Landscape chat opacity** | Make the theater-mode chat panel semi-transparent so you can see more of the stream behind it. |

Everything is opt-in and individually toggleable.

## Screenshots

<!-- Add screenshots to docs/ and reference them here -->

## Requirements

- Android 8.0 (API 26) or newer
- A rooted device with **[LSPosed](https://github.com/LSPosed/LSPosed)**, **or** a non-rooted device using **[LSPatch](https://github.com/LSPosed/LSPatch)**
- The official Twitch app installed

## Installation

### Rooted — LSPosed

1. Download `purpletv-xp.apk` from the [latest release](https://github.com/alienware377/purpletv-xposed/releases/latest).
2. Install it like a normal app.
3. Open LSPosed → **Modules** → enable **PurpleTV XP**.
4. In the module's scope list, tick **Twitch**.
5. Force-stop and reopen Twitch.

### Non-rooted — LSPatch

1. Download `purpletv-xp.apk` from the [latest release](https://github.com/alienware377/purpletv-xposed/releases/latest).
2. In LSPatch, select your installed Twitch app, choose **Integrated** patch mode, and add `purpletv-xp.apk` as an embedded module.
3. Install the patched Twitch APK that LSPatch produces.

> Your Twitch login is preserved across reinstalls as long as the signing key stays the same.

## Usage

Open Twitch and start watching any channel. Third-party emotes load automatically for that channel.

- **Emote autocomplete** — just start typing an emote name in the chat box.
- **Add a favorite** — long-press any Twitch or subscriber emote in the native emote picker.
- **Remove a favorite** — long-press it in the favorites bar.
- **Settings** — long-press the chat input box to open the module's settings.

## Building from source

```bash
git clone https://github.com/alienware377/purpletv-xposed.git
cd purpletv-xposed/xposed
./gradlew :app:assembleRelease
```

The unsigned APK lands in `app/build/outputs/apk/release/`. Sign it with any key before installing.

## How it works

The Twitch Android app is obfuscated with R8, and class and method names change on every release — which is why most Twitch mods break within weeks of an update. This module deliberately avoids naming obfuscated classes. Instead it anchors on things R8 cannot rename:

- **Android framework classes** (`Activity`, `EditText`, `ViewGroup`) hooked directly.
- **Resource entry names** (`getResourceEntryName`) — resource IDs survive obfuscation and are package-independent.
- **Kept public model packages** such as `tv.twitch.android.shared.chat.pub.messages.data`, which R8 preserves.
- **Twitch's own GraphQL gateway** for channel points, rather than the UI layer.

The result is a module that keeps working across Twitch updates far longer than smali-patch-based mods.

## Compatibility

Tested against recent Twitch Android releases. Because the module avoids obfuscated identifiers, it generally survives app updates. If a feature stops working after a Twitch update, please [open an issue](https://github.com/alienware377/purpletv-xposed/issues) with your Twitch version number.

## FAQ

**Does this get my account banned?**
The module reads third-party emote APIs and uses Twitch's own GraphQL endpoints with your existing session. It does not automate chat messages or viewing. That said, any client modification is against Twitch's Terms of Service — use it at your own risk.

**Does it block ads?**
No. Ad blocking is deliberately not implemented.

**Does it work without root?**
Yes, via LSPatch. See [Installation](#installation).

**Why do some favorites show as text instead of an image?**
The emote image is cached per channel. Favorites from a channel you are not currently watching fall back to their name until the image is fetched.

## Contributing

Issues and pull requests are welcome. When reporting a bug, include your Android version, Twitch app version, and whether you are using LSPosed or LSPatch.

## Credits

Emote data is provided by the [7TV](https://7tv.app), [BetterTTV](https://betterttv.com) and [FrankerFaceZ](https://frankerfacez.com) public APIs. Not affiliated with, endorsed by, or connected to Twitch Interactive, Inc.

## License

See [LICENSE](../LICENSE).

---

<sub>**Keywords:** twitch android mod, 7tv android, bttv android, ffz android, twitch xposed module, lsposed twitch, lspatch twitch, third party emotes twitch mobile, twitch emotes android app, betterttv android, frankerfacez android, twitch channel points auto claim, twitch chat transparency, purpletv, twitch mobile emotes</sub>
