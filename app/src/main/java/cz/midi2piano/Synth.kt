package cz.midi2piano

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Jednoduchý aditivní syntezátor klavíru (náhrada za FluidSynth + soundfont).
 * Neinharmonické parciály, rychlejší útlum vyšších parciálů, tlumítko při puštění klávesy a lehká dozvuk.
 * Pravá ruka velocity 82, levá 68 – stejně jako ve skriptu.
 */
object Synth {
    const val SR = 44100

    private class N(val s: Int, val e: Int, val p: Int, val vel: Int)

    fun render(R: List<Note>, L: List<Note>, speed: Double, progress: (Float) -> Unit = {}): ShortArray {
        val notes = ArrayList<N>()
        for ((vel, list) in listOf(82 to R, 68 to L)) for (n in list) {
            notes.add(N((n.s / speed * SR).toInt(), (n.e / speed * SR).toInt(), n.p, vel))
        }
        notes.sortBy { it.s }
        val endNote = notes.maxOfOrNull { it.e } ?: SR
        val total = endNote + SR * 3
        val seg = SR * 8
        val nSeg = (total + seg - 1) / seg
        val mix = FloatArray(total)
        val done = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(max(2, min(4, Runtime.getRuntime().availableProcessors())))
        try {
            val futures = (0 until nSeg).map { si ->
                pool.submit(Callable {
                    val a = si * seg
                    val b = min(total, a + seg)
                    val env = FloatArray(SR * 14)
                    for (n in notes) {
                        if (n.s >= b) break
                        val rel = (n.e - n.s).coerceAtLeast(SR / 20)
                        val len = min(rel + (SR * 0.7).toInt(), SR * 12)
                        if (n.s + len <= a) continue
                        addNote(mix, a, b, n, len, env)
                    }
                    progress(done.incrementAndGet().toFloat() / nSeg * 0.9f)
                })
            }
            futures.forEach { it.get() }
        } finally {
            pool.shutdown()
        }
        reverb(mix)
        progress(0.95f)
        // normalizace (náhrada za loudnorm) + jemný soft-clip
        var sum = 0.0
        var cnt = 0
        for (v in mix) if (abs(v) > 1e-4f) { sum += v.toDouble() * v; cnt++ }
        val rms = if (cnt > 0) sqrt(sum / cnt) else 1.0
        val gain = min(60.0, 0.16 / max(rms, 1e-6))
        val out = ShortArray(total)
        for (i in 0 until total) out[i] = (tanh(mix[i] * gain) * 30000.0).toInt().toShort()
        progress(1f)
        return out
    }

    private fun addNote(mix: FloatArray, a: Int, b: Int, n: N, len: Int, env: FloatArray) {
        val p = n.p
        val f0 = 440.0 * 2.0.pow((p - 69) / 12.0)
        val bInh = 0.00008 * 1.05.pow((p - 21).toDouble())
        val tau0 = (7.0 * 2.0.pow(-(p - 40) / 18.0)).coerceIn(0.6, 9.0)
        val amp0 = (n.vel / 127.0).pow(1.3) * 0.16
        // rozsah vzorků této noty uvnitř segmentu
        val i0 = max(a, n.s) - n.s          // první vzorek noty, který spadá do segmentu
        val i1 = min(b, n.s + len) - n.s
        if (i1 <= i0) return
        val cnt = i1 - i0
        val rel = (n.e - n.s).coerceAtLeast(SR / 20)
        val relTau = 0.07 + 0.08 * (1.0 - ((p - 21) / 87.0).coerceIn(0.0, 1.0))
        // obálka: útok + puštění
        var relCur = if (i0 > rel) exp(-(i0 - rel) / (relTau * SR)) else 1.0
        val relF = exp(-1.0 / (relTau * SR))
        for (k in 0 until cnt) {
            val i = i0 + k
            val at = if (i < 110) i / 110f else 1f
            if (i >= rel) { if (i > rel) relCur *= relF; env[k] = (at * relCur).toFloat() } else env[k] = at
        }
        // parciály
        var nMax = 0
        while (nMax < 14 && f0 * (nMax + 1) < 9500.0) nMax++
        if (nMax == 0) nMax = 1
        val wts = DoubleArray(nMax) { j ->
            val h = j + 1
            (0.35 + 0.65 * abs(sin(PI * h * 0.115))) / h.toDouble().pow(0.95)
        }
        val ws = wts.sum()
        for (j in 0 until nMax) {
            val h = j + 1
            val fh = h * f0 * sqrt(1.0 + bInh * h * h)
            if (fh > 10000.0) break
            val tau = tau0 / (1.0 + 0.5 * (h - 1))
            val amp = amp0 * wts[j] / ws * 6.0
            val w = 2.0 * PI * fh / SR
            val cr = cos(w)
            val ci = sin(w)
            var re = cos(w * i0)
            var im = sin(w * i0)
            val df = exp(-1.0 / (tau * SR))
            var ac = amp * exp(-i0 / (tau * SR))
            val o = n.s + i0
            for (k in 0 until cnt) {
                mix[o + k] += (ac * im * env[k]).toFloat()
                ac *= df
                val t = re * cr - im * ci
                im = re * ci + im * cr
                re = t
            }
        }
    }

    /** Schroederův dozvuk – jemný, jen aby zvuk nebyl "suchý". */
    private fun reverb(x: FloatArray) {
        val delays = intArrayOf(1687, 1601, 2053, 2251)
        val fb = floatArrayOf(0.80f, 0.79f, 0.77f, 0.76f)
        val wet = FloatArray(x.size)
        for ((ci, d) in delays.withIndex()) {
            val buf = FloatArray(d)
            var p = 0
            val g = fb[ci]
            for (i in x.indices) {
                val y = buf[p]
                buf[p] = x[i] + y * g
                wet[i] += y
                p++
                if (p == d) p = 0
            }
        }
        // dva all-pass filtry
        for (d in intArrayOf(556, 441)) {
            val buf = FloatArray(d)
            var p = 0
            for (i in wet.indices) {
                val y = buf[p]
                val inp = wet[i]
                val v = inp + y * 0.5f
                buf[p] = v
                wet[i] = y - v * 0.5f
                p++
                if (p == d) p = 0
            }
        }
        for (i in x.indices) x[i] += wet[i] * 0.07f
    }
}

object Wav {
    fun write(file: File, pcm: ShortArray, sr: Int) {
        RandomAccessFile(file, "rw").use { f ->
            f.setLength(0)
            val dataLen = pcm.size * 2
            val h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()); h.putInt(36 + dataLen); h.put("WAVE".toByteArray())
            h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1)
            h.putInt(sr); h.putInt(sr * 2); h.putShort(2); h.putShort(16)
            h.put("data".toByteArray()); h.putInt(dataLen)
            f.write(h.array())
            val chunk = java.nio.ByteBuffer.allocate(1 shl 16).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            var i = 0
            while (i < pcm.size) {
                chunk.clear()
                val n = min(pcm.size - i, chunk.capacity() / 2)
                for (k in 0 until n) chunk.putShort(pcm[i + k])
                f.write(chunk.array(), 0, n * 2)
                i += n
            }
        }
    }
}

/** Přehrávání PCM přes AudioTrack. Časová osa = lead (ticho) + pcm. */
class PcmPlayer(private val pcm: ShortArray, private val sr: Int, private val lead: Int) {
    private var track: AudioTrack? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var base = 0
    @Volatile var playing = false
        private set

    val total get() = lead + pcm.size
    val duration get() = total.toDouble() / sr

    private fun build(): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sr).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(max(minBuf, sr / 4 * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    /** spustí od dané sekundy */
    @Synchronized
    fun play(fromSec: Double) {
        halt()
        base = (fromSec * sr).toInt().coerceIn(0, total)
        val tr = build()
        track = tr
        running = true
        playing = true
        tr.play()
        val start = base
        thread = Thread {
            val buf = ShortArray(4096)
            var pos = start
            while (running && pos < total) {
                val n = min(buf.size, total - pos)
                for (i in 0 until n) { val idx = pos + i - lead; buf[i] = if (idx < 0) 0 else pcm[idx] }
                val w = try { tr.write(buf, 0, n) } catch (e: Exception) { -1 }
                if (w <= 0) break
                pos += w
            }
        }.also { it.isDaemon = true; it.start() }
    }

    @Synchronized
    fun pause() { track?.pause(); playing = false }

    @Synchronized
    fun resume() { track?.play(); playing = track != null }

    /** aktuální pozice v sekundách */
    fun position(): Double {
        val tr = track ?: return base.toDouble() / sr
        val p = base + tr.playbackHeadPosition
        if (p >= total) playing = false
        return min(p, total).toDouble() / sr
    }

    private fun halt() {
        running = false
        playing = false
        track?.let { try { it.pause(); it.flush(); it.release() } catch (_: Exception) {} }
        track = null
        thread?.join(300)
        thread = null
    }

    @Synchronized
    fun release() { halt() }
}
