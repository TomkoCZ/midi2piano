package cz.midi2piano

import android.graphics.pdf.PdfDocument
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

object Pdf {
    fun write(score: Score, out: File, progress: (Float) -> Unit = {}) {
        val eg = Engraver(score)           // vlastní instance (Paint není sdílený s náhledem)
        val doc = PdfDocument()
        try {
            for (i in 0 until eg.pageCount) {
                val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, i + 1).create())
                val sc = 595f / Engraver.PW
                page.canvas.scale(sc, sc)
                eg.draw(page.canvas, i)
                doc.finishPage(page)
                progress((i + 1f) / eg.pageCount)
            }
            FileOutputStream(out).use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
    }
}

object Aac {
    /** pcm (mono 16 bit) → AAC v MP4 kontejneru (.m4a). [lead] = počet vzorků ticha na začátku. */
    fun encode(pcm: ShortArray, sr: Int, lead: Int, out: File, progress: (Float) -> Unit = {}) {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sr, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 160_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        if (out.exists()) out.delete()
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val total = lead + pcm.size
        var pos = 0
        var inDone = false
        var eos = false
        var track = -1
        var started = false
        val info = MediaCodec.BufferInfo()
        try {
            while (!eos) {
                if (!inDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val buf = codec.getInputBuffer(ii)!!
                        buf.clear()
                        val n = min(buf.remaining() / 2, total - pos)
                        val pts = pos * 1_000_000L / sr
                        if (n <= 0) {
                            codec.queueInputBuffer(ii, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            buf.order(ByteOrder.LITTLE_ENDIAN)
                            for (k in 0 until n) {
                                val idx = pos + k - lead
                                buf.putShort(if (idx < 0) 0 else pcm[idx])
                            }
                            codec.queueInputBuffer(ii, 0, n * 2, pts, 0)
                            pos += n
                            if (pos % 20 == 0) progress(pos.toFloat() / total)
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    started = true
                } else if (oi >= 0) {
                    val ob = codec.getOutputBuffer(oi)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && started) {
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        muxer.writeSampleData(track, ob, info)
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos = true
                }
            }
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
            try { if (started) muxer.stop() } catch (_: Exception) {}
            muxer.release()
        }
        progress(1f)
    }
}

object VideoExport {
    /**
     * Vyrenderuje video (H.264) bez zvuku do [out]; snímky se kreslí přes Surface kodeku.
     * [seconds] = délka včetně úvodu.
     */
    fun encodeVideo(scene: Scene, speed: Double, seconds: Double, fps: Int, out: File, progress: (Float) -> Unit) {
        val w = Scene.W.toInt()
        val h = Scene.H.toInt()
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        codec.start()
        if (out.exists()) out.delete()
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val info = MediaCodec.BufferInfo()
        var track = -1
        var started = false
        var outIdx = 0L
        val frames = (seconds * fps).toInt() + 1

        fun drain(untilEos: Boolean) {
            while (true) {
                val oi = codec.dequeueOutputBuffer(info, if (untilEos) 10_000 else 0)
                if (oi == MediaCodec.INFO_TRY_AGAIN_LATER) { if (untilEos) continue else return }
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.outputFormat); muxer.start(); started = true
                } else if (oi >= 0) {
                    val ob = codec.getOutputBuffer(oi)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && started) {
                        info.presentationTimeUs = outIdx * 1_000_000L / fps   // vlastní časová osa
                        outIdx++
                        ob.position(info.offset); ob.limit(info.offset + info.size)
                        muxer.writeSampleData(track, ob, info)
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }

        try {
            for (i in 0 until frames) {
                val canvas = try { surface.lockHardwareCanvas() } catch (e: Exception) { surface.lockCanvas(null) }
                try {
                    scene.draw(canvas, i.toDouble() / fps, speed)
                } finally {
                    surface.unlockCanvasAndPost(canvas)
                }
                drain(false)
                if (i % 15 == 0) progress(i.toFloat() / frames)
            }
            codec.signalEndOfInputStream()
            drain(true)
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
            surface.release()
            try { if (started) muxer.stop() } catch (_: Exception) {}
            muxer.release()
        }
        progress(1f)
    }

    /** spojí video (bez zvuku) a audio (.m4a) do jednoho MP4 */
    fun mux(video: File, audio: File, out: File) {
        val vx = MediaExtractor().apply { setDataSource(video.path) }
        val ax = MediaExtractor().apply { setDataSource(audio.path) }
        fun pick(e: MediaExtractor, prefix: String): Int {
            for (i in 0 until e.trackCount) if (e.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true) return i
            error("Chybí stopa $prefix")
        }
        val vt = pick(vx, "video/")
        val at = pick(ax, "audio/")
        vx.selectTrack(vt); ax.selectTrack(at)
        if (out.exists()) out.delete()
        val mux = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val vi = mux.addTrack(vx.getTrackFormat(vt))
        val ai = mux.addTrack(ax.getTrackFormat(at))
        mux.start()
        val buf = ByteBuffer.allocate(2 shl 20)
        val info = MediaCodec.BufferInfo()
        try {
            while (true) {
                val vtime = vx.sampleTime
                val atime = ax.sampleTime
                if (vtime < 0 && atime < 0) break
                val useV = atime < 0 || (vtime in 0..atime)
                val ex = if (useV) vx else ax
                val idx = if (useV) vi else ai
                buf.clear()
                val size = ex.readSampleData(buf, 0)
                if (size < 0) { if (useV) { vx.advance(); if (vx.sampleTime < 0 && atime < 0) break }; continue }
                info.set(0, size, ex.sampleTime,
                    if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                mux.writeSampleData(idx, buf, info)
                ex.advance()
            }
        } finally {
            try { mux.stop() } catch (_: Exception) {}
            mux.release()
            vx.release(); ax.release()
        }
    }
}
