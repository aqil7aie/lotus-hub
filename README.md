# Lotus Shell

A Gecko-powered shell for self-hosted SillyTavern / Neconyan — the same idea
as Tavernium (a light wrapper that loads your own server address and adds
phone-side features), but on Mozilla's Gecko engine instead of Chromium's
WebView.

Why: on Aqil's phone, Chromium is fast on first launch and then drags by
itself; the ST family has always behaved best on Gecko. This shell is the
definitive test of whether Neconyan's stutter is the engine or Neconyan
itself — and, if Gecko wins, the daily driver for it.

## Status: v0 skeleton

What exists now: an address bar + GeckoView + remembering the last server
address. That is it, on purpose — one short step at a time.

## Roadmap (in order)

1. Multi-server list: SillyTavern + Neconyan + more, one tap to switch
   (Tavernium holds one server only — this is the first real delta).
2. Full Arabic UI (strings moved to `strings.xml`, RTL layout).
3. Back navigation (`session.goBack`).
4. Keep-alive: foreground service + content-script visibility handling.
5. Reply notifications: listen for the ST `MESSAGE_RECEIVED` event.
6. Downloads routed to the Downloads folder.
7. Burn-in guard overlay for long waits.
8. In-app updates with signature + SHA-256 checks (Tavernium-style).

## Build

CI builds the debug APK on every push to `main` (see
`.github/workflows/build.yml`). Local build needs Android SDK + Gradle 8.7:

```
gradle :app:assembleDebug
```

Release signing is TODO until the keystore secrets exist (see the workflow
file — nothing is faked there).

## Notes

- Native Android (Kotlin) only: no Flutter binding for GeckoView exists,
  so this cannot be a Flutter app the way Luna/ForkSilly are.
- Only dependency is GeckoView itself. No other third-party libraries.
- Clean-room code: the architecture is informed by Tavernium's own public
  README description (which documents its approach in detail); no
  Tavernium code is copied here.
- License: TBD by Aqil.
- Package `com.aqil.lotusshell` and the name "Lotus Shell" are changeable
  in one word — they are placeholders until he fixes them.
