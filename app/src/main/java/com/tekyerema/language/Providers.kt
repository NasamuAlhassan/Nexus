package com.tekyerema.language

import android.util.Log
import com.tekyerema.voice.Lang
import com.tekyerema.voice.Lid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.io.IOException

/**
 * The language layer's provider SPI (B-18/X-11). Speech recognition and synthesis keep the voice package's
 * [com.tekyerema.voice.AsrProvider] / [com.tekyerema.voice.TtsProvider] contracts; this file adds the two roles the
 * voice package never had — translation and language ID — plus the health/quota guard every network provider runs
 * behind, so a quota-exhausted or unreachable API is skipped fast instead of stalling each turn.
 *
 * Language codes are always the app's own ([Lang.GH_EN], [Lang.TWI]); each client maps them to its API's codes.
 */

/** Machine translation between the app's languages. Returns null when it can't (unsupported pair, failure). */
interface TranslationProvider {
    val name: String
    suspend fun translate(text: String, from: String, to: String): String?
}

/** Text language ID. The hot path uses the on-device [Lid]; an API that reports the spoken language alongside its
 *  transcript feeds that report through the ASR response instead (see `response.languagePath`). */
interface LanguageIdentifier {
    val name: String
    /** The language and a 0..1 confidence, or null when there is nothing to go on. */
    fun identify(text: String): Pair<String, Double>?
}

/** The on-device lexicon LID, offline and instant. */
object OnDeviceLid : LanguageIdentifier {
    override val name = "on-device-lid"
    override fun identify(text: String): Pair<String, Double>? {
        val d = Lid.detect(text)
        return if (d.twi == 0.0 && d.eng == 0.0) null else d.lang to d.confidence
    }
}

/** Tries translators in order; the first non-blank answer wins. */
class FallbackTranslator(private val chain: List<TranslationProvider>) : TranslationProvider {
    override val name = "fallback(" + chain.joinToString(",") { it.name } + ")"
    override suspend fun translate(text: String, from: String, to: String): String? {
        for (p in chain) {
            val out = try { p.translate(text, from, to) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            if (!out.isNullOrBlank()) return out
        }
        return null
    }
}

/** Implemented by providers that can say, without a network call, whether they are worth trying right now. */
interface HealthAware {
    fun available(): Boolean
}

/** Why a provider call failed — decides how long the provider rests. */
enum class FailureKind {
    /** 403/429 or a quota message: the account is out of calls; rest for the Retry-After or the quota cooldown. */
    QUOTA,
    /** 401: the key is wrong or missing; rest long — retrying cannot help until someone fixes local.properties. */
    AUTH,
    /** Timeout, connection failure, 5xx: count it; rest after a few in a row. */
    TRANSIENT,
    /** 4xx other than the above, or a reply we couldn't parse: usually a config-shape mistake; counted. */
    BAD_RESPONSE,
    /** The provider is resting; no call was made. */
    UNAVAILABLE
}

class ProviderFailure(val kind: FailureKind, message: String, val retryAfterMs: Long? = null) : Exception(message)

/**
 * Circuit breaker for one API account (a quota group). QUOTA and AUTH rest the provider at once; TRANSIENT and
 * BAD_RESPONSE rest it after [Policy.failuresBeforeRest] in a row, each rest twice the last (capped). A success
 * closes the breaker. Resting providers are skipped by the router, so the turn goes straight to the next provider
 * or the on-device engine — the user never waits on an API we already know is down.
 */
class ProviderHealth(
    val id: String,
    private val policy: Policy = Policy(),
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    data class Policy(
        val failuresBeforeRest: Int = 2,
        val transientRestMs: Long = 60_000L,
        val quotaRestMs: Long = 15 * 60_000L,
        val authRestMs: Long = 60 * 60_000L,
        val maxRestMs: Long = 6 * 60 * 60_000L
    )

    @Volatile private var restUntil = 0L
    @Volatile private var consecutive = 0
    @Volatile private var trips = 0
    @Volatile var lastFailure: FailureKind? = null; private set

    fun available(): Boolean = clock() >= restUntil

    /** Milliseconds until the provider may be tried again (0 when available). */
    fun restingForMs(): Long = (restUntil - clock()).coerceAtLeast(0L)

    @Synchronized fun onSuccess() { consecutive = 0; trips = 0; restUntil = 0L; lastFailure = null }

    @Synchronized fun onFailure(kind: FailureKind, retryAfterMs: Long? = null) {
        if (kind == FailureKind.UNAVAILABLE) return
        lastFailure = kind
        val rest = when (kind) {
            FailureKind.QUOTA -> retryAfterMs?.takeIf { it > 0 } ?: policy.quotaRestMs
            FailureKind.AUTH -> policy.authRestMs
            else -> {
                consecutive++
                if (consecutive < policy.failuresBeforeRest) return
                consecutive = 0
                trips++
                policy.transientRestMs shl (trips - 1).coerceAtMost(8)
            }
        }
        restUntil = clock() + rest.coerceAtMost(policy.maxRestMs)
    }

    fun status(): String = if (available()) "$id: ok" else "$id: resting ${restingForMs() / 1000}s after ${lastFailure}"
}

/** One [ProviderHealth] per quota group, shared by the ASR, TTS and MT clients of the same account. */
object ProviderHealthRegistry {
    private val byGroup = java.util.concurrent.ConcurrentHashMap<String, ProviderHealth>()
    fun of(group: String): ProviderHealth = byGroup.getOrPut(group) { ProviderHealth(group) }
    fun statusLine(): String = byGroup.values.joinToString("; ") { it.status() }.ifBlank { "no network speech providers" }
}

/**
 * Runs one provider call under its [health] and a coroutine backstop timeout ([backstopMs]; the HTTP client has its
 * own connect/call timeouts — this catches anything that slips past them). Records the outcome and turns every
 * failure into a [ProviderFailure]. A user "stop" (plain cancellation) passes through untouched and is never held
 * against the provider.
 */
class ProviderGuard(val health: ProviderHealth, private val backstopMs: Long) {
    suspend fun <T> run(block: suspend () -> T): T {
        if (!health.available()) throw ProviderFailure(FailureKind.UNAVAILABLE, health.status())
        val result = try {
            withTimeout(backstopMs) { block() }
        } catch (e: TimeoutCancellationException) {
            health.onFailure(FailureKind.TRANSIENT); throw ProviderFailure(FailureKind.TRANSIENT, "${health.id} timed out after ${backstopMs}ms")
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProviderFailure) {
            health.onFailure(e.kind, e.retryAfterMs); throw e
        } catch (e: IOException) {
            health.onFailure(FailureKind.TRANSIENT); throw ProviderFailure(FailureKind.TRANSIENT, "${health.id} network: ${e.message}")
        } catch (e: Exception) {
            health.onFailure(FailureKind.BAD_RESPONSE); throw ProviderFailure(FailureKind.BAD_RESPONSE, "${health.id}: ${e.message}")
        }
        health.onSuccess()
        return result
    }

    companion object {
        /** Log a provider failure without any user content (privacy.logcatContent). */
        fun log(tag: String, id: String, e: Throwable) {
            val kind = (e as? ProviderFailure)?.kind?.name ?: e.javaClass.simpleName
            Log.w("Tekyerema", "[$tag] $id failed ($kind): ${e.message?.take(160)}")
        }
    }
}
