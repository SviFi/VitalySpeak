# VitalySpeak

Voice dictation for Android that works in any app. Tap the floating button, speak in your own
languages (mixing them freely), tap again, and clean, punctuated text appears in the field you
are typing in.

- **Any app:** the button appears whenever a text field is active.
- **Your languages:** choose one primary language and any others you mix in, from the ~100
  that Whisper supports.
- **AI cleanup:** filler words, false starts and punctuation are fixed; nothing is translated
  or added. Styles: Natural, Polished, Notes or your own instructions.
- **Commands:** while recording, tap *Command* (or press volume-down) and tell the AI what to
  do with the text: "make this a list", "translate the last paragraph into Finnish"…
- **Long recordings:** audio is saved to disk in parts and transcribed while you speak, so
  nothing is lost even after a crash; unfinished dictations can be retried from History.
- **Private by design:** your own Groq API key, no developer servers, no analytics.
- **21 interface languages.**

## How it works

1. Audio is recorded on the phone (16 kHz mono) and sent to Groq's Whisper with your key.
2. The transcript is cleaned up by a Groq chat model (`openai/gpt-oss-120b` by default).
3. The text is inserted at the cursor through the Android Accessibility API (or pasted /
   left on the clipboard if a field doesn't accept it).

## Build

```
./gradlew testDirectDebugUnitTest
./gradlew assembleDirectRelease    # APK for direct install (self-updating)
./gradlew bundlePlayRelease        # AAB for Google Play
```

Flavours: `direct` checks GitHub releases for updates; `play` gets updates only from
Google Play. CI (`.github/workflows/build.yml`) builds both on every push and publishes
`build-N` releases from `main`. The Play bundle is signed with the upload key from the
`UPLOAD_KEYSTORE_B64`, `UPLOAD_STORE_PASSWORD`, `UPLOAD_KEY_ALIAS`, `UPLOAD_KEY_PASSWORD`
repository secrets.

`recommended.json` holds the currently recommended Groq models; the app reads it when
checking for model changes.

## Licence

© 2026 Vitaly Stockman. All rights reserved. See `LICENSE`.
