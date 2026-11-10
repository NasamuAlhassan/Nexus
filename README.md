# Nexus

Nexus is an accessibility-first, voice-first phone agent for blind and low-vision users, built as an Android app
(Kotlin, single module `app`, package `com.tekyerema`). You say what you want in plain English, Nexus works the phone
through the Android accessibility framework, checks the result, and tells you what happened.

It began as **Tɛkyerɛma** for the MTN × UG HCI Lab "Tɛkyerɛma Pa" Hackathon 2026 (top-five finalist) and was renamed
Nexus in October 2026. The code still carries the original package name.

## What it does

- Wake word "Nexus" on the device (sherpa-onnx keyword spotter), then speech in, speech out.
- Messaging and calls through the real apps: WhatsApp, Telegram, SMS, the dialler, with every send read back first.
- Asks instead of guessing: two contacts with the same name, three numbers for one person.
- Memory across requests ("remember the meeting is in N Block, room 3"), corrections mid-request ("make it six").
- Reads the screen out loud, recovers when something gets in the way (a notification shade, a missing button).
- Mobile Money stops at the PIN: it never sees, stores or types a PIN.
- Works offline for the common requests (on-device English recogniser and planner); uses a cloud model when online.

## Repository layout

| Path | What |
|---|---|
| `app/src/main/java/com/tekyerema/agent` | the planner loop, memory, corrections |
| `app/src/main/java/com/tekyerema/tools` | the actions the agent can take |
| `app/src/main/java/com/tekyerema/policy`, `verifier` | safety rules below the model; the "did it really happen" checks |
| `app/src/main/java/com/tekyerema/voice` | wake word, recognisers, speech output |
| `app/src/main/java/com/tekyerema/accessibility` | the accessibility service that reads and operates the screen |
| `app/src/main/java/com/tekyerema/money` | Mobile Money and USSD flows |
| `app/src/main/assets/config` | runtime configuration (JSON) |
| `app/src/test` | JVM unit tests (JUnit 4) |
| `ussdsim` | a tiny debug-only USSD simulator APK for emulator testing |

This repository holds the app code only. Recordings, evaluation data, training notebooks and the project notes live
in the private working repository.

## Building

Android Studio or the Gradle wrapper. API keys are read from a gitignored `local.properties` at the project root
(debug builds embed them); the app runs without them in offline mode.

```
./gradlew :app:testDebugUnitTest --offline      # unit tests
./gradlew :app:assembleDebug -PslimApk          # debug APK without the bundled offline English model
```

## Licence

Not yet chosen. All rights reserved until a licence file is added.
