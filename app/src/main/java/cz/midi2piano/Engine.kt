package cz.midi2piano

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Kvantizovaná nota (480 tiků = čtvrťová). */
data class QN(val s: Int, val e: Int, val p: Int)

class Grp(val s: Int, val e: Int, val ps: List<Int>)

val KS_MAJ = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
val KS_MIN = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)
val LY_SH = arrayOf("c", "cis", "d", "dis", "e", "f", "fis", "g", "gis", "a", "ais", "b")
val LY_FL = arrayOf("c", "des", "d", "es", "e", "f", "ges", "g", "as", "a", "bes", "b")
val LET_SH = intArrayOf(0, 0, 1, 1, 2, 3, 3, 4, 4, 5, 5, 6)
val ALT_SH = intArrayOf(0, 1, 0, 1, 0, 0, 1, 0, 1, 0, 1, 0)
val LET_FL = intArrayOf(0, 1, 1, 2, 2, 3, 4, 4, 5, 5, 6, 6)
val ALT_FL = intArrayOf(0, -1, 0, -1, 0, 0, -1, 0, -1, 0, -1, 0)

/** Názvy not do padajících not: cz (H) / en (B) */
fun noteNames(kind: String, sharp: Boolean): Array<String>? = when (kind) {
    "cz" -> if (sharp) arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "B", "H")
    else arrayOf("C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "B", "H")
    "en" -> if (sharp) arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    else arrayOf("C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B")
    else -> null
}

class Key(val tonic: Int, val mode: String, val sharp: Boolean) {
    val names get() = if (sharp) LY_SH else LY_FL
    val lyName get() = names[tonic]
    val label get() = (if (sharp) listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    else listOf("C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B"))[tonic] +
            if (mode == "minor") " moll" else " dur"

    /** počet křížků (>0) nebo bé (<0) v předznamenání */
    val signature: Int
        get() {
            val major = if (mode == "major") tonic else (tonic + 3) % 12
            return if (sharp) mapOf(0 to 0, 7 to 1, 2 to 2, 9 to 3, 4 to 4, 11 to 5, 6 to 6)[major] ?: 0
            else -(mapOf(5 to 1, 10 to 2, 3 to 3, 8 to 4, 1 to 5)[major] ?: 0)
        }
}

object Theory {
    fun detectKey(notes: List<Note>): Pair<Int, String> {
        val pc = DoubleArray(12)
        for (n in notes) pc[n.p % 12] += max(1.0, n.e - n.s)
        var best: Triple<Double, Int, String>? = null
        for (tonic in 0 until 12) for ((mode, prof) in listOf("major" to KS_MAJ, "minor" to KS_MIN)) {
            val a = DoubleArray(12) { pc[(tonic + it) % 12] }
            val ma = a.sum() / 12
            val mb = prof.sum() / 12
            var num = 0.0; var da = 0.0; var db = 0.0
            for (i in 0 until 12) {
                num += (a[i] - ma) * (prof[i] - mb)
                da += (a[i] - ma) * (a[i] - ma)
                db += (prof[i] - mb) * (prof[i] - mb)
            }
            val den = sqrt(da * db).let { if (it == 0.0) 1.0 else it }
            val r = num / den
            if (best == null || r > best.first) best = Triple(r, tonic, mode)
        }
        return best!!.second to best.third
    }

    /** "e-minor", "bb-major", "f#-minor" */
    fun parseKey(txt: String): Pair<Int, String> {
        val t = txt.lowercase().substringBefore("-")
        val m = if (txt.contains("-")) txt.lowercase().substringAfter("-") else ""
        var base = mapOf('c' to 0, 'd' to 2, 'e' to 4, 'f' to 5, 'g' to 7, 'a' to 9, 'b' to 11)[t[0]] ?: 0
        for (ch in t.drop(1)) base += if (ch == '#' || ch == 's') 1 else if (ch == 'b') -1 else 0
        return ((base % 12) + 12) % 12 to (if (m.startsWith("min")) "minor" else "major")
    }

    fun sharpPref(tonic: Int, mode: String): Boolean {
        val major = if (mode == "major") tonic else (tonic + 3) % 12
        return major in setOf(0, 7, 2, 9, 4, 11, 6)
    }
}

class Hands(val rh: List<Note>, val lh: List<Note>, val mode: String)

fun splitHands(tracks: List<List<Note>>, modeIn: String, split: Int): Hands {
    val all = tracks.flatten()
    var mode = modeIn
    if (mode == "auto") {
        mode = if (tracks.size == 2) {
            val m = tracks.map { t -> t.sumOf { it.p }.toDouble() / t.size }
            if (abs(m[0] - m[1]) >= 7) "tracks" else "pitch"
        } else "pitch"
    }
    return if (mode == "tracks") {
        require(tracks.size >= 2) { "Rozdělení podle stop vyžaduje alespoň 2 stopy s notami." }
        val tr = tracks.sortedBy { t -> -t.sumOf { it.p }.toDouble() / t.size }
        Hands(tr[0], tr.drop(1).flatten(), mode)
    } else {
        Hands(all.filter { it.p >= split }, all.filter { it.p < split }, mode)
    }
}

class Grid(ts: Pair<Int, Int>, notes: List<Note>) {
    val num = ts.first
    val den = ts.second
    val beat: Int = when (den) {
        4 -> 480
        2 -> 960
        8 -> if (num % 3 == 0) 720 else 240
        else -> 480
    }
    val bar = num * (1920 / den)
    val half: Int? = if (bar % (2 * beat) == 0) bar / 2 else null
    val trip = HashSet<Int>()

    init {
        if (beat == 480) for (n in notes) {
            val r = n.s % 480.0
            if (min(abs(r - 160), abs(r - 320)) <= 25) trip.add(Math.floor(n.s / 480.0).toInt())
        }
    }

    fun snap(v: Double): Int {
        if (beat == 480) {
            var bt = Math.floor(v / 480.0).toInt()
            var r = v - bt * 480
            if (r > 440) { bt += 1; r = 0.0 }
            val g = if (bt in trip) 160 else 120
            return bt * 480 + Math.rint(r / g).toInt() * g
        }
        return Math.rint(v / 120.0).toInt() * 120
    }

    fun unit(s: Int): Int = if (beat == 480 && (s / 480) in trip) 160 else 120
}

fun quantize(ns: List<Note>, grid: Grid, total: Int?): List<QN> {
    var q = ns.map { Triple(grid.snap(it.s), grid.snap(it.e), it.p) }
    if (total != null) q = q.map { Triple(it.first, min(it.second, total), it.third) }
    val ons = q.map { it.first }.distinct().sorted().toIntArray()
    val res = ArrayList<QN>()
    for ((s, e0, p) in q) {
        var e = max(e0, s + grid.unit(s))
        var idx = ons.binarySearch(s)
        idx = if (idx >= 0) idx + 1 else -idx - 1
        if (idx < ons.size && abs(e - ons[idx]) <= 160) e = ons[idx]
        if (total != null) e = min(e, total)
        if (e > s) res.add(QN(s, e, p))
    }
    return res
}

fun groups(ns: List<QN>): List<Grp> {
    val by = HashMap<Pair<Int, Int>, MutableSet<Int>>()
    for (n in ns) by.getOrPut(n.s to n.e) { java.util.TreeSet() }.add(n.p)
    return by.map { (k, v) -> Grp(k.first, k.second, v.sorted()) }
        .sortedWith(compareBy({ it.s }, { -it.ps.last() }))
}

/** Rozdělení do max. 2 hlasů. Vrací (skupina, hlas 1/2) a počet zkrácených tónů. */
fun lanes(gs: List<Grp>): Pair<List<Pair<Grp, Int>>, Int> {
    val out = ArrayList<Pair<Grp, Int>>()
    var i = 0
    var cut = 0
    while (i < gs.size) {
        var j = i
        var end = gs[i].e
        while (j + 1 < gs.size && gs[j + 1].s < end) { j++; end = max(end, gs[j].e) }
        val cl = gs.subList(i, j + 1)
        if (cl.size == 1) out.add(cl[0] to 1)
        else {
            val l = arrayOf(ArrayList<Grp>(), ArrayList<Grp>())
            val fr = intArrayOf(0, 0)
            for (g in cl) {
                val k: Int
                if (fr[0] <= g.s) k = 0
                else if (fr[1] <= g.s) k = 1
                else {
                    k = 0
                    val pg = l[0].last()
                    l[0][l[0].size - 1] = Grp(pg.s, g.s, pg.ps)
                    cut++
                    if (g.s - pg.s <= 0) l[0].removeAt(l[0].size - 1)
                }
                l[k].add(g)
                fr[k] = g.e
            }
            if (l[1].isEmpty()) l[0].forEach { out.add(it to 1) }
            else {
                val m = l.map { x -> x.sumOf { it.ps.last() }.toDouble() / x.size }
                val up = if (m[0] >= m[1]) 0 else 1
                val lo = 1 - up
                l[up].forEach { out.add(it to 1) }
                l[lo].forEach { out.add(it to 2) }
            }
        }
        i = j + 1
    }
    return out to cut
}

/** Výsledek analýzy – vše, co potřebují noty, zvuk i video. */
class Score(
    val title: String,
    val bpm: Double,
    val tmap: List<Pair<Double, Double>>,
    val ts: Pair<Int, Int>,
    val key: Key,
    val handsMode: String,
    val grid: Grid,
    val nb: Int,
    val rh: List<QN>,
    val lh: List<QN>,
    val noteCount: Int,
    val perLine: Int,
) {
    /** noty v sekundách (konstantní tempo) pro zvuk a video */
    val R: List<Note>
    val L: List<Note>
    val endSec: Double

    init {
        val spt = 60.0 / bpm / 480.0
        R = rh.map { Note(it.s * spt, it.e * spt, it.p) }.sortedWith(compareBy({ it.s }, { it.e }, { it.p }))
        L = lh.map { Note(it.s * spt, it.e * spt, it.p) }.sortedWith(compareBy({ it.s }, { it.e }, { it.p }))
        endSec = (R + L).maxOfOrNull { it.e } ?: 1.0
    }
}

class Options(
    val title: String,
    val hands: String = "auto",     // auto | tracks | pitch
    val split: Int = 60,
    val keyOverride: String? = null,
    val perLine: Int = 4,
    val names: String = "cz",       // cz | en | none
)

object Analyzer {
    fun analyze(m: MidiData, o: Options): Score {
        val h = splitHands(m.tracks, o.hands, o.split)
        val all = h.rh + h.lh
        val (tonic, mode) = if (o.keyOverride != null) Theory.parseKey(o.keyOverride) else Theory.detectKey(all)
        val key = Key(tonic, mode, Theory.sharpPref(tonic, mode))
        val grid = Grid(m.ts, all)
        val lastOn = all.maxOf { grid.snap(it.s) }
        val nb = lastOn / grid.bar + 1
        val total = nb * grid.bar
        return Score(o.title, m.bpm, m.tmap, m.ts, key, h.mode, grid, nb,
            quantize(h.rh, grid, total), quantize(h.lh, grid, total), all.size, o.perLine)
    }
}
