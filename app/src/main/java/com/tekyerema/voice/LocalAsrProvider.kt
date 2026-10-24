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

    /** A model for [lang] the app may fall back to when the online recogniser can't answer (quota, outage, offline):
     *  one with its own model.json — the clean whisper-base Twi model of 2 October 2026 — never the legacy tiny-twi
     *  layout, which was trained on the GOLD clips. */
    fun trustedFor(lang: String): Boolean = false
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
 * Phase 8 HYBRID ASR router — language-aware, offline-first, observable (§12/§13). Implements the
 * provisional routing policy from PHASE2_ASR_DELIVERY.md:
 *
 *   Twi           → on-device Twi model. NOTE: the currently-deployed tekyerema-whisper-tiny-twi is
 *                    DISQUALIFIED (2026-09-19 audit: GOLD was in its training set ×20 — its "1.000 on GOLD"
 *                    was memorisation, not recognition; NC-tainted lineage). Replace with the v2 base int8
 *                    export (Phase 9.1). Online Twi = the UG HCI Lab ASR API once its client is registered
 *                    ([SpeechProviders], `providers.speech.asr.online.twi`).
 *   Gh-EN/unknown → an online Gh-EN provider WHEN one is configured AND we're connected; otherwise the
 *                    on-device Gh-EN tiny.
 *
 * Offline safety: when no endpoint is configured or there's no network, the online leg is NEVER attempted
 * (isOnlineConfigured() is false) — so basic device control never hangs waiting on a cloud API, and an
 * online failure degrades to on-device rather than breaking. Every routing decision is logged. ASR only
 * produces text; the agent brain is entirely separate (no command shortcuts here).
 */
class HybridAsr(
    private val onDevice: SpeechToRaw,
    /** The online provider to use for a given effective language, or null to stay on-device. This is
     *  language-aware (§12/§13): each language gets the provider configured for it (the HCI Lab API);
     *  null ⇒ on-device. Callers also fold network state in here (return null when offline), so the
     *  online leg is never attempted without a network. */
    private val onlineFor: (lang: String?) -> AsrProvider?,
    private val forceLanguage: () -> String? = { null },
    private val sessionLanguage: () -> String = { Lang.GH_EN }
) : AsrProvider {
    private val local = LocalAsrProvider(onDevice, sessionLanguage)
    override val name = "hybrid(local=${onDevice.name})"

    override suspend fun transcribe(wav: ByteArray, sessionLanguage: String, forceLanguage: String?): Transcript {
        // J-15 Secure Input Mode: the mic is open only to hear "done" while the user types a PIN / password / code, and
        // a screen reader may be speaking those digits. That audio never leaves the phone — on-device or nothing (the
        // panel's Done button still works), and no online leg or fallback is ever tried.
        if (com.tekyerema.core.Privacy.secureStep) {
            if (onDevice.ready) return local.transcribe(wav, sessionLanguage, forceLanguage)
            throw AsrException("Secure step: speech is only recognised on the phone here — tap Done when you've finished.")
        }
        val lang = forceLanguage ?: this.forceLanguage()          // effective language (null = both/unknown)

        // §C.6 — on-device→LID→escalate two-pass for AUTO mode, used ONLY when there is no DIRECT online auto
        // provider (i.e. no code-switch-capable recogniser). It transcribes on-device, detects the language, and
        // escalates a confident-Twi utterance to the online Twi provider — so English audio is never sent to a
        // Twi-only recogniser. When a code-switching provider is configured, onlineFor(null) is non-null and we
        // skip straight to it. A forced language also skips this.
        if (lang == null && onDevice.ready && onlineFor(null) == null) {
            val twiOnline = onlineFor(Lang.TWI)
            if (twiOnline != null) {
                val localTr = try { local.transcribe(wav, sessionLanguage, null) } catch (e: AsrException) { null }
                val det = localTr?.let { Lid.detect(it.raw) }
                val isTwi = det != null && det.lang == Lang.TWI && det.confidence >= 0.5
                Log.i("Tekyerema", "[ASR] two-pass auto: local='${com.tekyerema.core.Privacy.content(localTr?.raw?.take(40))}' lid=${det?.lang}/${det?.confidence} -> ${if (isTwi) "escalate TWI→online" else "keep on-device/Gh-EN"}")
                if (isTwi) {
                    try { return twiOnline.transcribe(wav, sessionLanguage, Lang.TWI).copy(fallbackUsed = false) }
                    catch (e: AsrException) {
                        // §10.3 — only a TRUSTED on-device Twi model (its own model.json; never the GOLD-trained tiny-twi)
                        // takes over; otherwise an online-Twi failure fails honestly, not to garbage.
                        if (onDevice.trustedFor("twi")) return twiLocal(wav, sessionLanguage, Lang.TWI, TWI_SERVICE_DOWN)
                        throw AsrException(TWI_SERVICE_DOWN)
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
                // an online 403/429/quota failure must NOT silently degrade to it and speak confident garbage.
                // Surface the outage honestly instead.
                if (lang == Lang.TWI && onDevice.trustedFor("twi")) {
                    // The clean on-device Twi model (2 October 2026) answers when the HCI Lab can't — quota spent, outage.
                    Log.w("Tekyerema", "[ASR] Twi online provider failed → the trusted on-device Twi model: ${e.message}")
                    return twiLocal(wav, sessionLanguage, forceLanguage, TWI_SERVICE_DOWN)
                }
                if (lang == Lang.TWI) {
                    Log.w("Tekyerema", "[ASR] Twi online provider failed and offline Twi is off — failing honestly: ${e.message}")
                    throw AsrException(TWI_SERVICE_DOWN)
                }
                // Kusaal is heard only by the Space: the on-device English/Twi models would write confident nonsense.
                if (lang == Lang.KUS) {
                    Log.w("Tekyerema", "[ASR] Kusaal online provider failed — failing honestly: ${e.message}")
                    throw AsrException(KUS_SERVICE_DOWN)
                }
                Log.w("Tekyerema", "[ASR] online failed → on-device fallback: ${e.message}")
                if (onDevice.ready) return local.transcribe(wav, sessionLanguage, forceLanguage).copy(fallbackUsed = true)
                throw e
            }
        }
        // Twi is heard online only for now (owner, 3 October 2026 — SherpaAsr.offlineTwi): say what to do instead.
        if (lang == Lang.TWI && !onDevice.trustedFor("twi")) throw AsrException(TWI_NEEDS_INTERNET)
        if (lang == Lang.TWI) return twiLocal(wav, sessionLanguage, forceLanguage, TWI_NEEDS_INTERNET).copy(fallbackUsed = false)
        if (lang == Lang.KUS) throw AsrException(KUS_NEEDS_INTERNET)
        if (onDevice.ready) return local.transcribe(wav, sessionLanguage, forceLanguage)   // offline / no endpoint
        // Nothing available: fail FAST and honestly (never a silent hang), steering the user to the fix.
        throw AsrException(
            if (lang == Lang.TWI) TWI_NEEDS_INTERNET
            else "You're offline and the on-device voice model isn't available."
        )
    }

    /**
     * The on-device Twi model, for a Twi turn the online hearing could not take. Any failure of its own — no room to load it
     * (AsrLowMemoryException), nothing heard, a load error — becomes [whenFails], which the controller says in Twi
     * (TWI_SERVICE_DOWN_TWI / TWI_NEEDS_INTERNET_TWI): never the English low-memory or "couldn't understand" line
     * (reliability audit, 20 Oct 2026).
     */
    private suspend fun twiLocal(wav: ByteArray, sessionLanguage: String, forceLanguage: String?, whenFails: String): Transcript =
        try { local.transcribe(wav, sessionLanguage, forceLanguage ?: Lang.TWI).copy(fallbackUsed = true) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            Log.w("Tekyerema", "[ASR] on-device Twi model could not answer (${e.javaClass.simpleName}: ${e.message}) → '$whenFails'")
            throw AsrException(whenFails)
        }
}

/** What the user hears when Kusaal can't be heard (the Space is its only recogniser). */
const val KUS_SERVICE_DOWN = "The Kusaal voice service isn't answering right now. Try again in a moment, or type it."
const val KUS_NEEDS_INTERNET = "Kusaal needs the internet. Try again when you're connected, or type it."

/** What the user hears when Twi can't be heard. */
const val TWI_SERVICE_DOWN = "The Twi voice service isn't answering right now. Say it in English, or try again in a moment."
const val TWI_NEEDS_INTERNET = "Twi needs the internet for now. Say it in English, or try again when you're connected."
/** The same in a Twi conversation — said in Twi, not as English sent through translation (A20e, 14 October 2026: the
 *  translation of the English line timed out and the user heard English). [owner: verify the Twi] */
const val TWI_SERVICE_DOWN_TWI = "Mante. Twi kasa no nnyɛ adwuma seesei. Ka no Borɔfo mu, anaa twɛn kakra na san ka bio."
const val TWI_NEEDS_INTERNET_TWI = "Twi hia intanɛt seesei. Ka no Borɔfo mu, anaa san ka bio sɛ intanɛt aba a."

/** Honest placeholder: no model bundled yet. Never fakes a transcript, never calls the network. */
object NotIntegratedSpeechToRaw : SpeechToRaw {
    override val name = "none(DEVICE_TEST_PENDING)"
    override val ready = false
    override suspend fun rawTranscript(wav: ByteArray, forceLanguage: String?): String? {
        Log.i("Tekyerema", "[ASR] local model not integrated — see docs/archive/reports/LOCAL_VOICE_ARCHITECTURE.md")
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
 * through [LanguagePipeline]. Twi model availability follows docs/archive/reports/ASR_MODEL_SHORTLIST.md (train/distil).
 */
object SherpaSpeechToRaw : SpeechToRaw {
    override val name = "sherpa-onnx(NOT_INTEGRATED)"
    override val ready = false   // flip to true only after the AAR + model assets exist and load
    override suspend fun rawTranscript(wav: ByteArray, forceLanguage: String?): String? = null
}
