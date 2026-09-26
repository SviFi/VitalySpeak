# VitalySpeak

Push-to-talk dictation for Android, tuned for mixed **Russian + English** speech.
Tap the floating dot, speak, tap again — the text lands in whatever field is focused.

Pipeline (one Groq API key):

1. **Speech-to-text:** Groq `whisper-large-v3`, language auto-detected, with a vocabulary hint for names.
2. **Cleanup (optional):** Groq `llama-3.3-70b-versatile` fixes punctuation, removes fillers
   (uh, um, ээ, ну, типа, как бы…), never translates, never answers what you dictated.

Based on [kafkasl/phone-whisper](https://github.com/kafkasl/phone-whisper) (Apache-2.0).
Changes: Groq instead of OpenAI, bilingual cleanup prompt, model pickers, a model/update
checker, TLS warm-up during recording, on-device models removed.

## Install

Download the latest `VitalySpeak-N.apk` from **Releases** on the phone, open it, allow
"install unknown apps" for your browser. Then in the app:

1. Grant the audio permission.
2. Enable the Accessibility Service (Settings → Accessibility → VitalySpeak).
3. Paste a Groq API key (console.groq.com/keys).

## Staying current

Nothing below runs while you dictate. It all happens when you open the app.

- **New models:** at most once a day (or via *Check for new models & updates*), the app
  reads Groq's `/models` list. It flags new models, warns you if your current model was
  retired, and lets you switch with one tap. You can also type any model ID.
- **Newer stable models:** stability comes from Groq's own docs (Production vs Preview).
  A newer version of the model you use (e.g. `whisper-large-v3` → `v4`) shows
  "⬆ Newer version… tap to switch". A newer stable model from a different line is shown
  too, flagged as "different model line". Tapping opens *Switch / Don't suggest / Compare
  all*, and after a switch a "↩ tap to undo" row appears. Preview and beta models are never
  pushed. `ALREADY_EVALUATED` in `ModelChecker.kt` lists existing models that were judged
  not better (e.g. `whisper-large-v3-turbo` for Russian).
- **Recommended models:** edit [`recommended.json`](recommended.json) in this repo. Every
  installed copy shows "★ Recommended… tap to switch" within a day, with no rebuild.
- **App updates:** every push to `main` is built by GitHub Actions and published as release
  `build-N`. The app shows "⬆ App update available" and opens the APK download.

## Build locally

```
./gradlew assembleRelease -PversionCode=1
```

The signing key in `keystore/` is committed on purpose, so that CI builds can install
over each other. This is fine for a personal sideloaded app. Anyone could sign an APK with
it, but that APK would still have to reach your phone some other way. The in-app updater
only ever offers releases from this repo.
