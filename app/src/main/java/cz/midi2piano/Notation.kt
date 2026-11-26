package cz.midi2piano

import java.util.TreeSet
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Délky not (v ticcích, 480 = čtvrťová) → LilyPond zápis. */
val TAB: Map<Int, String> = linkedMapOf(
    1920 to "1", 1440 to "2.", 960 to "2", 720 to "4.", 480 to "4",
    360 to "8.", 240 to "8", 180 to "16.", 120 to "16", 60 to "32",
)
val TAB_KEYS = intArrayOf(1920, 1440, 960, 720, 480, 360, 240, 180, 120, 60)

/** Jedna událost v taktu: nota/akord/pomlka. */
class Item(
    val rel: Int,              // začátek v taktu (ticky)
    val ticks: Int,            // skutečná délka
    val ps: List<Int>?,        // null = pomlka
    val tie: Boolean,          // vázání na další
    val notated: Int,          // zapsaná délka (u triol 1,5× skutečné)
    val tup: Int = 0,          // číslo triolové skupiny v taktu (0 = žádná)
    val fullBar: Boolean = false,
    val spacer: Boolean = false, // neviditelná pomlka (2. hlas)
) {
    var fermata = false
}

class StaffData(val v1: List<List<Item>>, val v2: List<List<Item>>?, val cut: Int)

private class Ev(val s: Int, val e: Int, val ps: List<Int>?)
private class Seg(val s: Int, val e: Int, val ps: List<Int>?, val to: Boolean)
private class Fl(val s: Int, val e: Int, val ps: List<Int>?, val tie: Boolean)

/** Port logiky rozvržení taktů z make_ly(): rozdělení not na zapsatelné délky, vázání, triol. */
class BarBuilder(val grid: Grid, val nb: Int) {
    private val BAR = grid.bar
    private val BEAT = grid.beat
    private val HALF = grid.half
    private val total = nb * BAR

    private fun ok(r: Int, d: Int): Boolean {
        if (d !in TAB || r + d > BAR) return false
        if (HALF != null && r != 0 && r < HALF && HALF < r + d) return false
        if (r / BEAT != (r + d - 1) / BEAT) {
            if (r % BEAT == 0) return true
            if (BEAT == 480 && r % 480 == 240 && d == 480) return true
            return false
        }
        return true
    }

    private fun pieces(r: Int, e: Int): List<Pair<Int, Int>> {
        if (ok(r, e - r)) return listOf(r to e)
        for (d in TAB_KEYS) {
            if (r + d <= e && ok(r, d)) {
                return listOf(r to r + d) + (if (r + d < e) pieces(r + d, e) else emptyList())
            }
        }
        throw IllegalStateException("Nelze rozdělit $r-$e")
    }

    private fun fill(items: List<Grp>): List<Ev> {
        val ev = ArrayList<Ev>()
        var t = 0
        for (g in items) {
            if (g.s > t) ev.add(Ev(t, g.s, null))
            ev.add(Ev(g.s, g.e, g.ps))
            t = g.e
        }
        if (t < total) ev.add(Ev(t, total, null))
        return ev
    }

    private fun fullItem(spacer: Boolean) = Item(0, BAR, null, false, BAR, fullBar = true, spacer = spacer)

    private fun render(ev: List<Ev>, spacer: Boolean): Map<Int, List<Item>> {
        val bars = java.util.TreeMap<Int, MutableList<Seg>>()
        for (x in ev) for (b in x.s / BAR..(x.e - 1) / BAR) {
            bars.getOrPut(b) { ArrayList() }.add(Seg(max(x.s, b * BAR), min(x.e, (b + 1) * BAR), x.ps, x.e > (b + 1) * BAR))
        }
        val out = HashMap<Int, List<Item>>()
        for ((b, segs) in bars) {
            val base = b * BAR
            if (segs.size == 1 && segs[0].ps == null && segs[0].s == base && segs[0].e == base + BAR) {
                out[b] = listOf(fullItem(spacer)); continue
            }
            val tb = HashSet<Int>()
            if (BEAT == 480) for (sg in segs) for (x in intArrayOf(sg.s, sg.e)) {
                val o = (x - base) % 480
                if (o != 0 && min(abs(o - 160), abs(o - 320)) < 5) tb.add((x - base) / 480)
            }
            val flat = ArrayList<Fl>()
            for (sg in segs) {
                val cuts = TreeSet<Int>()
                cuts.add(sg.s)
                for (bt in tb) for (x in intArrayOf(base + bt * 480, base + bt * 480 + 480)) if (sg.s < x && x < sg.e) cuts.add(x)
                val cl = cuts.toList() + sg.e
                for (k in 0 until cl.size - 1) flat.add(Fl(cl[k], cl[k + 1], sg.ps, (k < cl.size - 2) || sg.to))
            }
            val items = ArrayList<Item>()
            var k = 0
            var tupId = 0
            while (k < flat.size) {
                val f = flat[k]
                val bt = if (BEAT == 480) (f.s - base) / 480 else -1
                if (bt in tb && f.e <= base + bt * 480 + 480) {
                    tupId++
                    while (k < flat.size && flat[k].s >= base + bt * 480 && flat[k].e <= base + bt * 480 + 480) {
                        val g = flat[k]
                        val n = ((g.e - g.s) / 160).coerceIn(1, 3)
                        items.add(Item(g.s - base, g.e - g.s, g.ps, g.ps != null && g.tie, n * 240, tupId, spacer = spacer && g.ps == null))
                        k++
                    }
                } else {
                    for ((a, c) in pieces(f.s - base, f.e - base)) {
                        items.add(Item(a, c - a, f.ps, f.ps != null && (c != f.e - base || f.tie), c - a, spacer = spacer && f.ps == null))
                    }
                    k++
                }
            }
            out[b] = items
        }
        return out
    }

    fun staff(ns: List<QN>): StaffData {
        val (seq, cut) = lanes(groups(ns))
        val v1 = seq.filter { it.second == 1 }.map { it.first }
        val v2 = seq.filter { it.second == 2 }.map { it.first }
        val r1 = render(fill(v1), false)
        val r2 = if (v2.isNotEmpty()) render(fill(v2), true) else null
        val b1 = (0 until nb).map { r1[it] ?: listOf(fullItem(false)) }
        val b2 = r2?.let { m -> (0 until nb).map { m[it] ?: listOf(fullItem(true)) } }
        // fermata na první události posledního taktu (jako ve skriptu)
        val last = b1[nb - 1]
        if (!last[0].fullBar) last[0].fermata = true
        return StaffData(b1, b2, cut)
    }
}

/** značky "rit." / "a tempo" z tempo mapy */
fun tempoMarks(score: Score): Map<Int, String> {
    val marks = HashMap<Int, String>()
    var state = "n"
    for ((t, b) in score.tmap) {
        val bar = (t / score.grid.bar).toInt()
        if (state == "n" && b < score.bpm * 0.9) { marks[bar] = "rit."; state = "s" }
        else if (state == "s" && b >= score.bpm * 0.95) { marks[bar] = "a tempo"; state = "n" }
    }
    return marks.filterKeys { it < score.nb }
}

/** Zdroj pro LilyPond (stejný jako ve skriptu). */
object LilyPond {
    fun build(score: Score, rh: StaffData, lh: StaffData): String {
        val grid = score.grid
        val names = score.key.names
        fun nm(n: Int): String {
            val k = n / 12 - 4
            return names[n % 12] + (if (k > 0) "'".repeat(k) else ",".repeat(-k))
        }

        fun itemLy(i: Item): String {
            if (i.fullBar) return (if (i.spacer) "s1*" else "R1*") + "${grid.num}/${grid.den}"
            val d = TAB[i.notated] ?: "4"
            val ps = i.ps ?: return (if (i.spacer) "s" else "r") + d
            val chord = if (ps.size == 1) nm(ps[0]) else "<" + ps.joinToString(" ") { nm(it) } + ">"
            return chord + d + (if (i.tie) "~" else "") + (if (i.fermata) "\\fermata" else "")
        }

        fun barLy(items: List<Item>): String {
            val t = ArrayList<String>()
            var cur = 0
            for (i in items) {
                if (i.tup != cur) {
                    if (cur != 0) t.add("}")
                    if (i.tup != 0) t.add("\\tuplet 3/2 {")
                    cur = i.tup
                }
                t.add(itemLy(i))
            }
            if (cur != 0) t.add("}")
            return t.joinToString(" ")
        }

        val marks = tempoMarks(score).mapValues { "s1*0^\\markup \\italic \"${it.value}\"" }
        val per = score.perLine

        fun staffLy(name: String, d: StaffData, clef: String, mk: Map<Int, String>): String {
            val l = ArrayList<String>()
            for (b in 0 until score.nb) {
                var s = barLy(d.v1[b])
                if (b in mk) s = mk[b] + " " + s
                l.add(s)
                if ((b + 1) % per == 0 && b + 1 < score.nb) l.add("\\break")
            }
            val v1 = l.joinToString(" |\n").replace("\\break |", "\\break")
            val head = "\\new Staff = \"$name\" { \\clef $clef \\key ${score.key.lyName} \\${score.key.mode} " +
                    "\\time ${grid.num}/${grid.den} " + (if (name == "rh") "\\tempo 4=${Math.round(score.bpm)}" else "") + "\n"
            return if (d.v2 != null) {
                val v2 = (0 until score.nb).joinToString(" |\n") { barLy(d.v2[it]) }
                head + "<< \\new Voice { \\voiceOne $v1 \\bar \"|.\" } \\new Voice { \\voiceTwo $v2 } >> }"
            } else head + "$v1 \\bar \"|.\" }"
        }

        val safe = score.title.replace("\"", "'")
        return """\version "2.24.0"
\paper { #(set-paper-size "a4") top-margin=12\mm bottom-margin=12\mm left-margin=14\mm right-margin=14\mm indent=0 ragged-last-bottom=##f }
\header { title = "$safe" tagline = ##f }
#(set-global-staff-size 19)
\layout { \context { \Score \override BarNumber.font-size = -1 barNumberVisibility = #all-bar-numbers-visible \override BarNumber.break-visibility = #end-of-line-invisible \override SpacingSpanner.base-shortest-duration = #(ly:make-moment 1/16) } }
\score { \new PianoStaff << ${staffLy("rh", rh, "treble", marks)}
${staffLy("lh", lh, "bass", emptyMap())} >> \layout{} }
"""
    }
}
