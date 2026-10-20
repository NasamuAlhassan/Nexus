package com.tekyerema.voice

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The input of our Twi hearing model (tekyerema-asr-ctc-twi-v2, w2v-BERT 2.0 CTC) on the phone, for offline Twi (owner, 20 Oct
 * 2026: "let's make the Twi offline too"). A line-for-line port of transformers' SeamlessM4TFeatureExtractor: 16 kHz samples
 * scaled to 16-bit, 25 ms Povey-windowed frames every 10 ms, DC removed, pre-emphasis 0.97, 512-point power spectrum, 80 Kaldi
 * mel filters triangular in mel space (20 Hz - 8 kHz), natural log (floor 1.19e-7), then each mel bin normalised over the clip
 * (mean 0, variance 1 with ddof 1), padded to an even frame count, and two frames stacked into one row of 160. Checked against the Python output
 * (W2vBertFeaturesTest, fixture made by transformers 4.57). Pure.
 */
object W2vBertFeatures {
    const val SAMPLE_RATE = 16_000
    private const val FRAME = 400
    private const val HOP = 160
    private const val NFFT = 512
    private const val BINS = NFFT / 2 + 1
    const val MELS = 80
    const val STRIDE = 2
    private const val PREEMPH = 0.97
    private const val MEL_FLOOR = 1.192092955078125e-07

    private val window = DoubleArray(FRAME) { n -> (0.5 - 0.5 * cos(2 * PI * n / (FRAME - 1))).pow(0.85) }
    private val melBank: Array<DoubleArray> = melFilters()   // [BINS][MELS]
    private val cosT = DoubleArray(NFFT / 2) { cos(-2 * PI * it / NFFT) }
    private val sinT = DoubleArray(NFFT / 2) { sin(-2 * PI * it / NFFT) }

    /** Rows of 160 features for [samples] (16 kHz, -1..1), or an empty array when the clip is shorter than two frames. */
    fun compute(samples: FloatArray, padValue: Float = 1f): Array<FloatArray> {
        val frames = if (samples.size < FRAME) 0 else 1 + (samples.size - FRAME) / HOP
        if (frames < STRIDE) return emptyArray()
        val logMel = Array(frames) { DoubleArray(MELS) }
        val re = DoubleArray(NFFT); val im = DoubleArray(NFFT)
        for (f in 0 until frames) {
            val off = f * HOP
            var mean = 0.0
            for (i in 0 until FRAME) mean += samples[off + i] * 32768.0
            mean /= FRAME
            java.util.Arrays.fill(re, 0.0); java.util.Arrays.fill(im, 0.0)
            for (i in 0 until FRAME) re[i] = samples[off + i] * 32768.0 - mean
            for (i in FRAME - 1 downTo 1) re[i] -= PREEMPH * re[i - 1]
            re[0] *= 1 - PREEMPH
            for (i in 0 until FRAME) re[i] *= window[i]
            fft(re, im)
            val row = logMel[f]
            for (b in 0 until BINS) {
                val p = re[b] * re[b] + im[b] * im[b]
                val w = melBank[b]
                for (m in 0 until MELS) if (w[m] != 0.0) row[m] += w[m] * p
            }
            for (m in 0 until MELS) row[m] = ln(max(MEL_FLOOR, row[m]))
        }
        // Per-bin normalisation over the whole clip (numpy var with ddof=1, + 1e-7).
        for (m in 0 until MELS) {
            var s = 0.0
            for (f in 0 until frames) s += logMel[f][m]
            val mu = s / frames
            var v = 0.0
            for (f in 0 until frames) { val d = logMel[f][m] - mu; v += d * d }
            val sd = sqrt(v / (frames - 1) + 1e-7)
            for (f in 0 until frames) logMel[f][m] = (logMel[f][m] - mu) / sd
        }
        // The extractor pads to a multiple of the stride (pad_to_multiple_of=2) with the model's padding_value (1 in this
        // model's preprocessor_config.json) AFTER normalising, so an odd frame count gains one row of padValue.
        val rows = (frames + STRIDE - 1) / STRIDE
        return Array(rows) { r ->
            FloatArray(MELS * STRIDE) { j ->
                val f = r * STRIDE + j / MELS
                if (f < frames) logMel[f][j % MELS].toFloat() else padValue
            }
        }
    }

    /** In-place iterative radix-2 FFT of length [NFFT]. */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = NFFT
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val step = n / len
            for (i in 0 until n step len) {
                for (k in 0 until len / 2) {
                    val wr = cosT[k * step]; val wi = sinT[k * step]
                    val a = i + k; val b = a + len / 2
                    val xr = re[b] * wr - im[b] * wi
                    val xi = re[b] * wi + im[b] * wr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                }
            }
            len = len shl 1
        }
    }

    private fun hzToMel(f: Double) = 1127.0 * ln(1.0 + f / 700.0)

    /** transformers' mel_filter_bank(257, 80, 20, 8000, 16000, norm=None, mel_scale="kaldi", triangularize_in_mel_space=True). */
    private fun melFilters(): Array<DoubleArray> {
        val lo = hzToMel(20.0); val hi = hzToMel(SAMPLE_RATE / 2.0)
        val edges = DoubleArray(MELS + 2) { lo + (hi - lo) * it / (MELS + 1) }
        val binWidth = SAMPLE_RATE.toDouble() / ((BINS - 1) * 2)
        return Array(BINS) { b ->
            val fm = hzToMel(binWidth * b)
            DoubleArray(MELS) { m ->
                val down = -(edges[m] - fm) / (edges[m + 1] - edges[m])
                val up = (edges[m + 2] - fm) / (edges[m + 2] - edges[m + 1])
                max(0.0, min(down, up))
            }
        }
    }
}
