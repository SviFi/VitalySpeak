# VitalySpeak privacy

- Audio is recorded only between your two taps on the floating dot and is kept in memory, never written to disk.
- The recording is sent to Groq (api.groq.com) for transcription and, if cleanup is on, the text is sent to Groq for cleanup. See Groq's privacy policy.
- Your Groq API key is stored only in the app's private storage on the phone.
- When you open the app it may contact api.groq.com (model list), raw.githubusercontent.com (recommended models) and api.github.com (update check). No analytics, no tracking.
- The Accessibility Service is used only to insert dictated text into the focused field after you tap the dot.
