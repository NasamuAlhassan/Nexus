# Tɛkyerɛma

A voice-first accessibility agent for blind and low-vision Ghanaian users. You speak a goal naturally;
Tɛkyerɛma interprets it, operates the phone via the Android accessibility framework, verifies the result,
and speaks back. **Languages: Ghanaian English + Asante Twi only.**

It is **one app with two modes** — a local core that works with the internet off, extended (not replaced) by
online reasoning when connectivity exists.

## Architecture

The authoritative description is **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)** (24 Oct 2026; every component with its
source file); runtime model files are listed in [docs/MODELS.md](docs/MODELS.md); current status in
[docs/agent/PROJECT_STATE.md](docs/agent/PROJECT_STATE.md). In short:

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

- **Ghanaian English** — on the phone: our fine-tuned Parakeet 0.6B (bundled), whisper-base.en when memory is short, and
  the `GhanaWords` repairs for money and telco words; online: the phone's recogniser.
- **Asante Twi** — online: our own Space recogniser (w2v-BERT CTC, `tekyerema-asr-ctc-twi-v2`), then the UG HCI Lab;
  on the phone: the same recogniser as int8 ONNX (`OrtCtcAsr`, pushed to `files/ondevice/twi/asr`).
  The September whisper-base Twi results are history: `docs/archive/reports/TWI_ASR_COMBINED_REPORT.md`.

## ASR / brain / TTS

| layer | offline | online |
|---|---|---|
| ASR | Ghanaian-English Parakeet 0.6B fine-tune, **bundled in the APK** (`SherpaAsr`; `-PslimApk` leaves it out for a phone that already holds it); Twi CTC int8 on the phone (`speech.asr.offlineTwi` = true) | Twi: our Space (`space_asr`) → UG HCI Lab (`hcilab_asr`) |
| Brain | `LocalBrain` rules + the fast path + `MoneyPlan` / USSD navigator (deterministic, on-device; the Twi command matcher answers Twi here too) | Groq `openai/gpt-oss-120b` (OpenAI-compatible) → Gemini fallback; Twi that the matcher doesn't take goes translator → English brain with names/amounts as tags |
| TTS | our Piper Ghanaian-English voice (Kafui) and our Twi MMS-VITS voice, on the phone; the system engine last | Twi: our Space (`space_tts`) → UG HCI Lab TTS; Ghanaian English: Kafui on the Space |

## Device support (any Android phone, not one model)

Tɛkyerɛma is built to run on **any Android 8.0+ (minSdk 26) phone**, not a specific device:

- **No device lock-in** — no `Build.MODEL` checks; the APK bundles native libs for `arm64-v8a`, `armeabi-v7a` (32-bit
  Android Go phones) and `x86_64` (emulators). 32-bit x86 was dropped on 24 Oct 2026 (44 MB no phone could use).
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
./gradlew :app:testDebugUnitTest    # ~2,160 JVM unit tests (24 October 2026)
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

- **Twi offline is partial.** The Twi recogniser and voice run on the phone, but English→Twi translation exists only
  online, so offline replies are English except the fixed Twi lines and the local router's answers. The phone's Twi voice
  is slow on the A20e (~11 s to first audio, 21 Oct measurement). Ghanaian-language speech must be the UG HCI Lab's or the
  team's own models (~10 ASR + 15 TTS lab requests a day).
- **English speech:** the full APK bundles a fine-tuned Parakeet 0.6B for offline English ASR (≈631 MB; the `-PslimApk`
  build leaves it out); English replies use our own Ghanaian-English Piper voice (Kafui) with the system voice as backup.
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
