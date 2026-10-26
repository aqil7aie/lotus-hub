# Lotus Hub

<img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="120" alt="Lotus Hub icon">

[English](README.md) · [العربية](README.ar.md) · [中文](README.zh-CN.md)

## What is Lotus Hub?

Lotus Hub is a native Android app that works as a remote for SillyTavern —
it opens your local servers in full screen, as if they were standalone apps:
SillyTavern and its forks, and any other local server.

## How does it work?

1. **Add a server:** name + address + optional username and password, then **Save**.
   Saving works even if the server is offline, and it never opens the server.
2. **Tap a card once to enter** the server.
3. **Long-press a card** for options: Edit, Delete, Cancel.
4. **Connect** probes reachability only (never opens, never saves) and shows
   the result with the HTTP code. **Enter** opens the server without saving.
5. If the server asks for a specific profile from SillyTavern, enter it
   directly; if not, you can switch from inside the server itself with
   simple steps.
6. **The hint:** a small line above the first card row teaches both gestures.
   It disappears forever after one long-press and one enter together,
   and returns only if app data is wiped.
7. Image and file upload works from inside the server (system chooser).
8. Select menus inside the server work through a built-in bridge that reads
   the real options from the live page.

## Build steps

CI builds the release APK on every push to `main` (see
`.github/workflows/build.yml`). A local build needs the Android SDK + Gradle:

```
gradle :app:assembleRelease
```

The signing key is private and is never inside this repository.

## Private stays private

Anything private of yours (keys, passwords, personal data) stays with you
in Drive, and is never placed in this public repository.

## Fork and modify

This repository is open: fork it and modify it as you like.

## Found a bug?

If you find any error while using the app, leave the error here
(open an Issue) so it can be fixed.

## Notes

- Status: v0.5.2 — complete release.
- arm64-v8a only: GeckoView ships large native libraries per architecture.
- Clean-room code: the architecture is informed by Tavernium's own public
  description; no Tavernium code is copied here.
- Package: `com.aqil.lotusshell`.
- License: GPL-3.0 — see LICENSE.
- Copyright © 2026 Aqil Hani.
