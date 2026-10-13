# Tɛkyerɛma

A voice-first accessibility agent for blind and low-vision Ghanaian users. You speak a goal naturally;
Tɛkyerɛma interprets it, operates the phone via the Android accessibility framework, verifies the result,
and speaks back. **Languages: Ghanaian English + Asante Twi only.**

It is **one app with two modes** — a local core that works with the internet off, extended (not replaced) by
online reasoning when connectivity exists.

## Architecture

```
MIC → ASR → LanguagePipeline(LID+normalize+entities) → BRAIN(router) → TOOL → OBSERVE → VERIFY → RECOVER → TTS
```

- **Hybrid brain router** (`agent/RoutingBrain.kt`): the on-device `LocalBrain` handles simple/offline goals
  directly (fast path); it escalates to the cloud brain (Groq / Gemini) only when it can't ground the request
  **and** the network is up. Offline with no local match → an honest refusal, never a silent cloud call or a
  fabricated answer.
- **On-device LocalBrain** (`agent/LocalBrain.kt`): deterministic natural-language → structured tool call
  (open app, call, navigate, scroll, read screen, alarm/timer, volume, tap a visible label). Intent
  classification + generic argument extraction, not a phrase→command table.
- **Connectivity** (`core/NetworkMonitor.kt`): ONLINE / OFFLINE / DEGRADED, decided by the OS (§32).
- **Shared tool layer** (`tools/`): one vocabulary both brains use — `open_app, tap, long_press, swipe, drag,
  pinch, scroll, type, set_range, key_event (back/home/recents/volume), read_state, make_call, find_contact,
  search_contacts, search_messages, read_notifications, reply_to_notification, set_alarm, set_timer,
  set_volume/volume_up/volume_down, run_ussd, cannot_do`.
- **Safety below the model** (`policy/`): every action classified by the *resolved target's* semantics, not the
  tool name. Money/irreversible actions require explicit confirmation; FINANCIAL scope is never auto-granted;
  the agent stops before any PIN entry and never stores or sees a PIN.
- **Observe → verify → recover** (`verifier/`): a `Done` is only `COMPLETED` if the substantive change
  actually verified (the hallucination guard).
- **Tɛkyerɛma keyboard** (`ime/`, Settings › Keyboard): a Twi + English keyboard with ɛ and ɔ where Q and C are, swipe
  typing decided on the phone (`GestureDecoder`: the keys the finger crossed and turned on, aligned against the word lists),
  suggestions from a 20,000-word English list and the reviewed Twi list (optional learned words), numbers on the top row
  (slide up or long-press), a voice key, and keys TalkBack can explore. Nothing typed leaves the phone; suggestions, swipe
  and learning are off in password/PIN boxes and in the Secure Input step.

## Supported languages

- **Ghanaian English** — online ASR = Whisper-large-v3 (MIT) via the proxy; on-device English STT seam ready.
- **Asante Twi** — validated model `PrinceAlhassanNasamu/tekyerema-twi-whisper-base-waxal-financial-v1`
  (whisper-base + WAXAL + Financial-Inclusion, CC-BY-4.0). Entity 0.550 / WER 1.20 on the locked Twi GOLD set
  (an *entity-preservation* score, not "55% transcription"). Ship as **ONNX FP32** (bit-exact); naive dynamic
  INT8 is rejected (WER blow-up). See `TWI_ASR_COMBINED_REPORT.md`.

## ASR / brain / TTS

| layer | offline | online |
|---|---|---|
| ASR | Ghanaian-English Parakeet 0.6B fine-tune, **bundled in the APK** (`SherpaAsr`; `-PslimApk` leaves it out for a phone that already holds it); offline Twi hearing is switched off for now (`speech.asr.offlineTwi`) | Twi: UG HCI Lab Speech Gateway (`hcilab_asr`); English stays on-device |
| Brain | `LocalBrain` rules + the fast path + `MoneyPlan` / USSD navigator (deterministic, on-device; the Twi command matcher answers Twi here too) | Groq `openai/gpt-oss-120b` (OpenAI-compatible) → Gemini fallback; Twi that the matcher doesn't take goes translator → English brain with names/amounts as tags |
| TTS | Android system engine (English) + recorded lab clips for the fixed Twi frames | UG HCI Lab TTS (Twi, 15 requests/day); falls back to the system engine so it's never silent (§28) |

## Device support (any Android phone, not one model)

Tɛkyerɛma is built to run on **any Android 8.0+ (minSdk 26) phone**, not a specific device:

- **No device/ABI lock-in** — no `Build.MODEL` checks, no RAM gates, no `abiFilters`; the APK bundles native
  libs for `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`.
- **Resolution/DPI independent** — screen control reads the live window metrics and acts on each element's own
  on-screen bounds (never fixed coordinates); Compose UI is density-independent.
- **Graceful degradation** — the universal path (local brain + tool layer + accessibility control + online ASR +
  system TTS) works on every supported phone. The **only** RAM-sensitive part is the *optional* on-device ASR
  model: when integrated it must be **RAM-adaptive** (pick model size by device, ship sherpa for multiple ABIs)
  and fall back to online ASR on phones that can't host it. The Samsung A20e (2.8 GB) is only the low-end
  *test target* used to prove it runs on constrained hardware — nothing is optimized exclusively for it.

## Permissions

Microphone, accessibility service (screen control), contacts, phone (calls), notifications (read + direct
reply), system-alert-window (floating trigger), network-state. Requested with rationale in first-run onboarding.

## Build

```
./gradlew :app:assembleDebug        # → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest    # 1,716 unit tests (3 October 2026)
```
Requires the Android SDK (`local.properties` `sdk.dir`). Cloud-brain keys are injected from gitignored
`local.properties` (`GEMINI_API_KEY`, `GROQ_API_KEY`); the app runs offline without them.

## Install & demos

```
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Launch → grant mic + enable the Tɛkyerɛma accessibility service → tap the living-voice circle (or the floating
bubble) and speak. Demo goals: "Open WhatsApp", "Call Prince", "What's on my screen?", "Scroll down",
"Set an alarm for 6 a.m.", "Open Google Maps", and (online) "Search the web for the weather in Accra".
Test identity: **Prince / 0552093690 / 0508797323**.

## Known limitations (honest status)

- **Twi speech is online.** Twi ASR and the Twi voice go through the UG HCI Lab speech API (the hackathon's required
  path; ~10 ASR + 15 TTS requests a day on the test token). Offline, Twi speech input returns an honest "you're offline"
  line; typed Twi still works through the local router.
- **English speech:** the full APK bundles a fine-tuned Parakeet 0.6B for offline English ASR (≈631 MB; the `-PslimApk`
  build leaves it out and uses the phone's recogniser); English replies use the phone's own voice. No Ghanaian-English
  voice exists anywhere yet (our own Piper voice is a Kaggle work in progress).
- **Wake word:** "Hey Tɛkyerɛma" (openWakeWord heads on ONNX Runtime) is on by default; the orb, the floating bubble, the
  accessibility button and the assistant gesture also open the mic.
- **Device status (13 Oct 2026):** verified on the Pixel 4 emulator (API 35) for every flow that needs no SIM — the Mobile
  Money walk runs against a debug USSD simulator there — and on a Samsung A20e (Android 11, MTN) for the live flows up to
  the PIN. Nothing is ever automated past the MoMo PIN; the receipt is read from MTN's own screen and SMS.
- Offline, `LocalBrain` handles single-action and screen-read goals, the device fast path answers time/battery/volume and
  the like, and genuinely multi-step goals get an honest "I'm offline, so I can't do all of that" with the part it can do.

## Model licensing / attribution

- `openai/whisper-base` — MIT.
- WAXAL / WaxalNLP (Akan) — CC-BY-4.0 (Google).
- Financial-Inclusion Speech Dataset (Asante Twi) — CC-BY-4.0 — *Asamoah Owusu, D., Korsah, A., Quartey, B.,
  Nwolley Jnr., S., Sampah, D., Adjepon-Yamoah, D. and Omane Boateng, L., 2022, Ashesi-Org/Financial-Inclusion-
  Speech-Dataset (Ashesi University & Nokwary Technologies; Lacuna Fund).*
