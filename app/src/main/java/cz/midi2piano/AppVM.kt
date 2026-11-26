package cz.midi2piano

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** Jedna exportní položka (PDF, .ly, zvuk, video v dané rychlosti). */
class Job(val key: String, val label: String, val hint: String) {
    var progress by mutableFloatStateOf(0f)
    var status by mutableIntStateOf(0)      // 0 čeká, 1 běží, 2 hotovo, 3 chyba
    var msg by mutableStateOf("")
}

class AppVM(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("m2p", 0)

    // --- vstup
    var midi by mutableStateOf<MidiData?>(null)
    var fileName by mutableStateOf("")
    var title by mutableStateOf("")
    var hands by mutableStateOf("auto")
    var split by mutableIntStateOf(60)
    var keyChoice by mutableStateOf<String?>(null)
    var perLine by mutableIntStateOf(4)
    var names by mutableStateOf("cz")
    val speedChoices = listOf(1.0, 0.75, 0.55, 0.25)
    val speeds = mutableStateListOf(1.0, 0.55, 0.25)

    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    // --- výsledek
    var inResult by mutableStateOf(false)
    var score by mutableStateOf<Score?>(null)
    var engraver by mutableStateOf<Engraver?>(null)
    var scene by mutableStateOf<Scene?>(null)

    // --- zvuk / přehrávač
    var speed by mutableDoubleStateOf(1.0)
    var audioBusy by mutableStateOf(false)
    var audioProgress by mutableFloatStateOf(0f)
    var player by mutableStateOf<PcmPlayer?>(null)
    private val cache = object : LinkedHashMap<Double, ShortArray>(4, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<Double, ShortArray>?) = size > 2
    }

    // --- export
    var outDir by mutableStateOf<Uri?>(prefs.getString("outDir", null)?.let { Uri.parse(it) })
    val jobs = mutableStateListOf<Job>()
    private val mutex = Mutex()

    val stem: String get() = fileName.substringBeforeLast('.').ifBlank { "skladba" }

    fun setOutDir(u: Uri) {
        outDir = u
        prefs.edit().putString("outDir", u.toString()).apply()
    }

    fun load(uri: Uri) {
        busy = true; error = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cr = getApplication<Application>().contentResolver
                val bytes = cr.openInputStream(uri)!!.use { it.readBytes() }
                var name = "skladba.mid"
                cr.query(uri, null, null, null, null)?.use { c ->
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0 && c.moveToFirst()) name = c.getString(i) ?: name
                }
                val m = MidiParser.parse(bytes)
                fileName = name
                title = name.substringBeforeLast('.').replace('_', ' ').replace('-', ' ').trim()
                    .split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
                midi = m
                inResult = false
            } catch (e: Throwable) {
                error = e.message ?: e.toString()
            } finally { busy = false }
        }
    }

    fun build() {
        val m = midi ?: return
        busy = true; error = null
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val sc = Analyzer.analyze(m, Options(title.ifBlank { stem }, hands, split, keyChoice, perLine, names))
                val eg = Engraver(sc)
                val scn = Scene(sc.R, sc.L, sc.title, names, sc.key.sharp, sc.endSec)
                score = sc; engraver = eg; scene = scn
                cache.clear()
                player?.release(); player = null
                val sp = speeds.sortedDescending().ifEmpty { listOf(1.0) }
                speed = sp.first()
                jobs.clear()
                jobs.add(Job("pdf", "Klavírní noty (PDF)", "${stem}_noty.pdf"))
                jobs.add(Job("ly", "Zdroj LilyPond (.ly)", "${stem}_noty.ly"))
                jobs.add(Job("m4a", "Zvuk z notového zápisu (M4A/AAC)", "${stem}_zapis.m4a"))
                jobs.add(Job("wav", "Zvuk bez komprese (WAV)", "${stem}_zapis.wav"))
                for (s in sp) jobs.add(Job("mp4_$s", "Video „padající noty“ ${fmt(s)}×", "${stem}_tutorial_${fmt(s)}x.mp4"))
                inResult = true
            } catch (e: Throwable) {
                error = e.message ?: e.toString()
            } finally { busy = false }
        }
    }

    fun back() {
        player?.release(); player = null
        inResult = false
    }

    private fun fmt(s: Double) = if (s == s.toLong().toDouble()) s.toLong().toString() else s.toString()

    // ---------------------------------------------------------------- zvuk
    private fun rawPcm(sp: Double, progress: (Float) -> Unit = {}): ShortArray {
        synchronized(cache) { cache[sp]?.let { return it } }
        val sc = score!!
        val pcm = Synth.render(sc.R, sc.L, sp, progress)
        synchronized(cache) { cache[sp] = pcm }
        return pcm
    }

    fun selectSpeed(sp: Double) {
        speed = sp
        audioBusy = true; audioProgress = 0f
        player?.release(); player = null
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val pcm = rawPcm(sp) { audioProgress = it }
                player = PcmPlayer(pcm, Synth.SR, (Scene.T0 * Synth.SR).toInt())
            } catch (e: Throwable) {
                error = e.message
            } finally { audioBusy = false }
        }
    }

    // ---------------------------------------------------------------- export
    fun exportAll() { for (j in jobs) if (j.status != 1) start(j) }

    fun start(job: Job) {
        val tree = outDir ?: return
        val sc = score ?: return
        job.status = 1; job.progress = 0f; job.msg = ""
        viewModelScope.launch(Dispatchers.Default) {
            mutex.withLock {
                val ctx = getApplication<Application>()
                val files = ArrayList<File>()
                try {
                    fun tmp(name: String) = File(ctx.cacheDir, name).also { files.add(it) }
                    val (name, mime) = when {
                        job.key == "pdf" -> "${stem}_noty.pdf" to "application/pdf"
                        job.key == "ly" -> "${stem}_noty.ly" to "text/plain"
                        job.key == "m4a" -> "${stem}_zapis.m4a" to "audio/mp4"
                        job.key == "wav" -> "${stem}_zapis.wav" to "audio/x-wav"
                        else -> {
                            val s = job.key.removePrefix("mp4_").toDouble()
                            "${stem}_tutorial_${fmt(s)}x.mp4" to "video/mp4"
                        }
                    }
                    val out = tmp(name)
                    when {
                        job.key == "pdf" -> Pdf.write(sc, out) { job.progress = it }
                        job.key == "ly" -> out.writeText(Engraver(sc).lilypond(), Charsets.UTF_8)
                        job.key == "m4a" -> {
                            val pcm = rawPcm(1.0) { job.progress = it * 0.6f }
                            Aac.encode(pcm, Synth.SR, 0, out) { job.progress = 0.6f + it * 0.4f }
                        }
                        job.key == "wav" -> {
                            val pcm = rawPcm(1.0) { job.progress = it * 0.8f }
                            Wav.write(out, pcm, Synth.SR)
                        }
                        else -> {
                            val s = job.key.removePrefix("mp4_").toDouble()
                            val pcm = rawPcm(s) { job.progress = it * 0.2f }
                            val lead = (Scene.T0 * Synth.SR).toInt()
                            val aud = tmp("a_${job.key}.m4a")
                            val vid = tmp("v_${job.key}.mp4")
                            Aac.encode(pcm, Synth.SR, lead, aud)
                            job.progress = 0.25f
                            val secs = (lead + pcm.size).toDouble() / Synth.SR + 0.3
                            val scn = Scene(sc.R, sc.L, sc.title, names, sc.key.sharp, sc.endSec)
                            VideoExport.encodeVideo(scn, s, secs, 30, vid) { job.progress = 0.25f + it * 0.68f }
                            VideoExport.mux(vid, aud, out)
                        }
                    }
                    save(tree, out, name, mime)
                    job.progress = 1f; job.status = 2
                } catch (e: Throwable) {
                    job.status = 3; job.msg = e.message ?: e.toString()
                } finally {
                    files.forEach { it.delete() }
                }
            }
        }
    }

    private fun save(tree: Uri, file: File, name: String, mime: String) {
        val cr = getApplication<Application>().contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val doc = DocumentsContract.createDocument(cr, parent, mime, name) ?: error("Nelze vytvořit soubor v cílové složce")
        cr.openOutputStream(doc, "w")!!.use { o -> file.inputStream().use { it.copyTo(o) } }
    }

    override fun onCleared() { player?.release() }
}
