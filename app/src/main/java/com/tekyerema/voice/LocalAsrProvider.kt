package com.tekyerema.voice

import android.util.Log

/**
 * The on-device ASR seam (§34). A local speech model produces ONLY the raw transcript; the already-
 * local [LanguagePipeline] (LID + normalization + entities, unit-tested) turns it into a [Transcript].
 * This keeps the model swappable and the language processing model-agnostic — and it is genuinely
 * offline (no network anywhere in this path).
 */
interface SpeechToRaw {
    /** @return the raw transcript for a 16 kHz mono PCM16 WAV, or null if the model can't run. */
    suspend fun rawTranscript(wav: ByteArray, forceLanguage: String?): String?
    val name: String
    /** True only when a real model is loaded (assets present + runtime initialised). */
    val ready: Boolean
}

/**
 * Local (offline) ASR provider: `wav → SpeechToRaw (model) → LanguagePipeline → Transcript`. The
 * default model is [NotIntegratedSpeechToRaw] so the app compiles and behaves honestly before a
 * sherpa-onnx model is bundled — it reports unavailability rather than pretending or calling a
 * network. Swap in [SherpaSpeechToRaw] once a model is packaged + device-validated.
 */
class LocalAsrProvider(
    private val model: SpeechToRaw = NotIntegratedSpeechToRaw,
    private val sessionLanguage: () -> String = { Lang.GH_EN }
) : AsrProvider {
    override val name = "local(${model.name})"

    override suspend fun transcribe(wav: ByteArray, sessionLanguage: String, forceLanguage: String?): Transcript {
        if (!model.ready) throw AsrException("local ASR model not integrated yet (${model.name})")
        val raw = model.rawTranscript(wav, forceLanguage)
            ?: throw AsrException("local ASR produced no transcript (${model.name})")
        // Post-processing is fully local and model-agnostic.
        return LanguagePipeline.process(raw, sessionLanguage, forceLanguage, backend = model.name)
    }
}

/**
 * Online/offline ASR router (§14/§34): prefer the on-device model when it's ready (works offline), else
 * the online provider when connected, else an honest error the controller speaks aloud. Never silently
 * fails and never fabricates a transcript. Today `onDevice.ready` is false (no bundled model / no device
 * validation), so this is effectively "online when connected, clear message when offline" — the truth.
 *
 * Cross-device note: the on-device model is an OPTIONAL enhancement, not a per-phone lock-in. When bundled it
 * must be RAM-adaptive (choose model size by device, ship the native lib for multiple ABIs); on any phone that
 * can't host it, `onDevice.ready` stays false and this router simply uses online ASR — so the app runs
 * everywhere (minSdk 26+), never tuned exclusively for one device.
 */
class RoutingAsr(
    private val onDevice: SpeechToRaw,
    private val online: AsrProvider,
    private val isOnline: () -> Boolean,
    sessionLanguage: () -> String = { Lang.GH_EN }
) : AsrProvider {
    private val local = LocalAsrProvider(onDevice, sessionLanguage)
    override val name = "routing(local=${onDevice.name}; online=${online.name})"
    override suspend fun transcribe(wav: ByteArray, sessionLanguage: String, forceLanguage: String?): Transcript {
        if (isOnline()) {
            try { return online.transcribe(wav, sessionLanguage, forceLanguage) }            // Groq Whisper: fast + accurate
            catch (e: AsrException) {
                android.util.Log.w("Tekyerema", "[ASR] online failed → on-device fallback: ${e.message}")
                if (onDevice.ready) return local.transcribe(wav, sessionLanguage, forceLanguage)
                throw e
            }
        }
        if (onDevice.ready) return local.transcribe(wav, sessionLanguage, forceLanguage)      // offline: on-device
        throw AsrException("You're offline and the on-device voice model isn't available.")
    }
}

/**
 * Phase 8 HYBRID ASR router — language-aware, offline-first, observable (§12/§13). Implements the
 * provisional routing policy from PHASE2_ASR_DELIVERY.md:
 *
 *   Twi           → on-device Twi model. NOTE: the currently-deployed tekyerema-whisper-tiny-twi is
 *                    DISQUALIFIED (2026-09-19 audit: GOLD was in its training set ×20 — its "1.000 on GOLD"
 *                    was memorisation, not recognition; NC-tainted lineage). Replace with the v2 base int8
 *                    export (Phase 9.1). Online Twi = Khaya (Phase 3.x); network is used for Twi via Khaya.
 *   Gh-EN/unknown → the strong ONLINE Gh-EN model (whisper-large-v3-turbo-tekyerema-eng, 0.894) WHEN a
 *                    real endpoint is configured AND we're connected; otherwise the on-device Gh-EN tiny.
 *
 * Offline safety: when no endpoint is configured or there's no network, the online leg is NEVER attempted
 * (isOnlineConfigured() is false) — so basic device control never hangs waiting on a cloud API, and an
 * online failure degrades to on-device rather than breaking. Every routing decision is logged. ASR only
 * produces text; the agent brain is entirely separate (no command shortcuts here).
 */
class HybridAsr(
    private val onDevice: SpeechToRaw,
    /** The online provider to use for a given effective language, or null to stay on-device. This is
     *  language-aware (§12/§13): Twi → Khaya (natural-Twi ASR) when a key is set; Gh-EN → the strong
     *  whisper endpoint when configured; either returns null ⇒ on-device. Callers also fold network
     *  state in here (return null when offline), so the online leg is never attempted without a network. */
    private val onlineFor: (lang: String?) -> AsrProvider?,
    private val forceLanguage: () -> String? = { null },
    private val sessionLanguage: () -> String = { Lang.GH_EN }
) : AsrProvider {
    private val local = LocalAsrProvider(onDevice, sessionLanguage)
    override val name = "hybrid(local=${onDevice.name})"

    override suspend fun transcribe(wav: ByteArray, sessionLanguage: String, forceLanguage: String?): Transcript {
        val lang = forceLanguage ?: this.forceLanguage()          // effective language (null = both/unknown)

        // §C.6 — on-device→LID→escalate two-pass for AUTO mode, used ONLY when there is no DIRECT online auto
        // provider (i.e. no code-switch-capable recogniser like Abena's "twi-en"). It transcribes on-device,
        // detects the language, and escalates a confident-Twi utterance to Khaya — so English audio is never
        // sent to the Twi-only Khaya ASR. When Abena is configured, onlineFor(null) is non-null and we skip
        // straight to it (Abena handles code-switching itself). A forced language also skips this.
        if (lang == null && onDevice.ready && onlineFor(null) == null) {
            val twiOnline = onlineFor(Lang.TWI)
            if (twiOnline != null) {
                val localTr = try { local.transcribe(wav, sessionLanguage, null) } catch (e: AsrException) { null }
                val det = localTr?.let { Lid.detect(it.raw) }
                val isTwi = det != null && det.lang == Lang.TWI && det.confidence >= 0.5
                Log.i("Tekyerema", "[ASR] two-pass auto: local='${localTr?.raw?.take(40)}' lid=${det?.lang}/${det?.confidence} -> ${if (isTwi) "escalate TWI→Khaya" else "keep on-device/Gh-EN"}")
                if (isTwi) {
                    try { return twiOnline.transcribe(wav, sessionLanguage, Lang.TWI).copy(fallbackUsed = false) }
                    catch (e: AsrException) {
                        // §10.3 — on-device Twi is disqualified; a Khaya failure fails honestly, not to garbage.
                        throw AsrException("The Twi voice service is unavailable right now, and there isn't a trusted offline Twi model yet.")
                    }
                }
                // Not Twi: prefer a configured Gh-EN endpoint, else the on-device text we already have.
                val enOnline = onlineFor(null)
                if (enOnline != null) {
                    try { return enOnline.transcribe(wav, sessionLanguage, null).copy(fallbackUsed = false) }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    // Any failure (incl. a network IOException/timeout, not only AsrException) → on-device text if we have it.
                    catch (e: Exception) { if (localTr != null) return localTr.copy(fallbackUsed = true); throw AsrException(e.message ?: "online ASR failed") }
                }
                if (localTr != null) return localTr
            }
        }

        val online = onlineFor(lang)
        Log.i("Tekyerema", "[ASR] hybrid route lang=${lang ?: "auto"} " +
            "deviceReady=${onDevice.ready} -> ${if (online != null) "ONLINE(${online.name})" else "ON-DEVICE"}")
        if (online != null) {
            try {
                return online.transcribe(wav, sessionLanguage, forceLanguage).copy(fallbackUsed = false)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            // Catch ANY online failure (incl. a network IOException/timeout, not only AsrException) so a socket
            // timeout on the primary provider still reaches the on-device fallback / honest-outage path below.
            catch (e: Exception) {
                // §10.3 — for Twi, the on-device model is DISQUALIFIED (contaminated; Phase 9.1 pending), so a
                // Khaya 403/429/quota failure must NOT silently degrade to it and speak confident garbage.
                // Surface the outage honestly instead.
                if (lang == Lang.TWI) {
                    Log.w("Tekyerema", "[ASR] Twi online (Khaya) failed and on-device Twi is disqualified — failing honestly: ${e.message}")
                    throw AsrException("The Twi voice service is unavailable right now, and there isn't a trusted offline Twi model yet.")
                }
                Log.w("Tekyerema", "[ASR] online failed → on-device fallback: ${e.message}")
                if (onDevice.ready) return local.transcribe(wav, sessionLanguage, forceLanguage).copy(fallbackUsed = true)
                throw e
            }
        }
        if (onDevice.ready) return local.transcribe(wav, sessionLanguage, forceLanguage)   // offline / no endpoint
        // Nothing available: fail FAST and honestly (never a silent hang), steering the user to the fix.
        throw AsrException(
            if (lang == Lang.TWI) "The on-device Twi voice model isn't available."
            else "You're offline and the on-device voice model isn't available."
        )
    }
}

/** Honest placeholder: no model bundled yet. Never fakes a transcript, never calls the network. */
object NotIntegratedSpeechToRaw : SpeechToRaw {
    override val name = "none(DEVICE_TEST_PENDING)"
    override val ready = false
    override suspend fun rawTranscript(wav: ByteArray, forceLanguage: String?): String? {
        Log.i("Tekyerema", "[ASR] local model not integrated — see LOCAL_VOICE_ARCHITECTURE.md")
        return null
    }
}

/**
 * Scaffold for the sherpa-onnx on-device model (§34) — NOT wired into the UI, NOT counted as working.
 * Integration steps (kept as guidance, not compiled, until the AAR + a bundled model land):
 *
 *   // build.gradle: implementation("com.k2fsa.sherpa.onnx:sherpa-onnx:<ver>")  // arm64-v8a
 *   // assets: encoder.int8.onnx / decoder.int8.onnx / tokens.txt   (Whisper/Conformer, from phase2/train/export_onnx.py)
 *   //
 *   // private val recognizer = OfflineRecognizer(assetManager, OfflineRecognizerConfig(
 *   //   modelConfig = OfflineModelConfig(whisper = OfflineWhisperModelConfig(encoder, decoder),
 *   //                                     tokens = "tokens.txt", numThreads = 2)))
 *   // override suspend fun rawTranscript(wav, force): String {
 *   //   val s = recognizer.createStream(); s.acceptWaveform(pcmFloat(wav), 16000); recognizer.decode(s)
 *   //   return recognizer.getResult(s).text
 *   // }
 *
 * Language is chosen by which bundled model runs (English vs a Twi model); the raw text then flows
 * through [LanguagePipeline]. Twi model availability follows ASR_MODEL_SHORTLIST.md (train/distil).
 */
object SherpaSpeechToRaw : SpeechToRaw {
    override val name = "sherpa-onnx(NOT_INTEGRATED)"
    override val ready = false   // flip to true only after the AAR + model assets exist and load
    override suspend fun rawTranscript(wav: ByteArray, forceLanguage: String?): String? = null
}
