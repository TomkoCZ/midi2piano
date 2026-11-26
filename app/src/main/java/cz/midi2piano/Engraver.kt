package cz.midi2piano

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

private val TOP_STEP = intArrayOf(45, 33)   // horní linka: F5 / A3
private val BOT_STEP = intArrayOf(37, 25)   // dolní linka: E4 / G2
private val MID_STEP = intArrayOf(41, 29)   // střední linka: B4 / D3

private val SHARP_STEPS = arrayOf(intArrayOf(45, 42, 46, 43, 40, 44, 41), intArrayOf(31, 28, 32, 29, 26, 30, 27))
private val FLAT_STEPS = arrayOf(intArrayOf(41, 44, 40, 43, 39, 42, 38), intArrayOf(27, 30, 26, 29, 25, 28, 24))
private val SHARP_ORDER = intArrayOf(3, 0, 4, 1, 5, 2, 6)
private val FLAT_ORDER = intArrayOf(6, 2, 5, 1, 4, 0, 3)

class Beam(val items: List<PItem>, val up: Boolean)

class PItem(val it: Item, val staff: Int, val voice: Int, val bar: Int, val steps: IntArray, val accs: IntArray, var up: Boolean) {
    var x = 0f
    var sys = 0
    var tieTo: PItem? = null
    var tieFromPrev = false
    var beam: Beam? = null
    var barCenter = 0f

    fun flips(): BooleanArray {
        val n = steps.size
        val f = BooleanArray(n)
        if (up) { for (i in 1 until n) if (steps[i] - steps[i - 1] == 1 && !f[i - 1]) f[i] = true }
        else { for (i in n - 2 downTo 0) if (steps[i + 1] - steps[i] == 1 && !f[i + 1]) f[i] = true }
        return f
    }
}

private class Sys(
    val page: Int, val bars: IntRange, val rhTop: Float, val lhTop: Float, val scale: Float,
    val first: Boolean, val headerW: Float,
) {
    val barLeft = FloatArray(bars.last + 1)
    val barW = FloatArray(bars.last + 1)
}

/**
 * Vlastní sazba klavírních not (dvě osnovy, 2 hlasy, trioly, vazby, předznamenání…).
 * Kreslí se do android.graphics.Canvas v bodech A4 (595×842) – stejný kód slouží pro
 * náhled v aplikaci i pro export PDF.
 */
class Engraver(val score: Score) {
    companion object {
        const val PW = 595.28f
        const val PH = 841.89f
        const val SP = 4.75f          // výška mezery osnovy (LilyPond staff-size 19)
        private const val ML = 40f
        private const val MR = 40f
        private const val MT = 34f
        private const val MB = 34f
        private const val HW = 1.25f * SP
    }

    val rhData: StaffData
    val lhData: StaffData
    private val grid = score.grid
    private val key = score.key
    private val nb = score.nb
    private val sp = SP

    private val vi = Array(2) { Array(2) { arrayOfNulls<List<PItem>>(nb) } }   // [staff][voice][bar]
    private val systems = ArrayList<Sys>()
    private val marks = tempoMarks(score)

    // sloupce taktů
    private class Cols(val rels: IntArray, val pos: FloatArray, val width: Float)
    private val cols = arrayOfNulls<Cols>(nb)

    val pageCount: Int

    init {
        val bb = BarBuilder(grid, nb)
        rhData = bb.staff(score.rh)
        lhData = bb.staff(score.lh)
        prepare(0, rhData)
        prepare(1, lhData)
        computeColumns()
        pageCount = layout()
        link()
    }

    fun lilypond(): String = LilyPond.build(score, rhData, lhData)

    // ------------------------------------------------------------------ příprava položek
    private fun prepare(staff: Int, d: StaffData) {
        val letters = if (key.sharp) LET_SH else LET_FL
        val alts = if (key.sharp) ALT_SH else ALT_FL
        val keyAlt = IntArray(7)
        val sig = key.signature
        if (sig > 0) for (i in 0 until sig) keyAlt[SHARP_ORDER[i]] = 1
        if (sig < 0) for (i in 0 until -sig) keyAlt[FLAT_ORDER[i]] = -1
        val twoVoices = d.v2 != null
        for (b in 0 until nb) {
            val lists = arrayOf(d.v1[b], d.v2?.get(b) ?: emptyList())
            val state = HashMap<Int, Int>()
            val made = Array(2) { ArrayList<PItem>() }
            val order = ArrayList<Pair<Int, Item>>()
            for (v in 0..1) for (i in lists[v]) order.add(v to i)
            order.sortWith(compareBy({ it.second.rel }, { it.first }))
            val byItem = HashMap<Item, PItem>()
            for ((v, it) in order) {
                val ps = it.ps
                val steps = IntArray(ps?.size ?: 0)
                val accs = IntArray(ps?.size ?: 0) { 99 }
                if (ps != null) for ((i, p) in ps.withIndex()) {
                    val let = letters[p % 12]
                    val a = alts[p % 12]
                    val step = (p / 12) * 7 + let
                    steps[i] = step
                    val cur = state[step] ?: keyAlt[let]
                    if (cur != a) accs[i] = a
                    state[step] = a
                }
                val up = if (twoVoices) v == 0 else if (steps.isEmpty()) true
                else (steps.min() + steps.max()) / 2.0 < MID_STEP[staff]
                val p = PItem(it, staff, v, b, steps, accs, up)
                byItem[it] = p
                made[v].add(p)
            }
            for (v in 0..1) {
                made[v].sortBy { it.it.rel }
                beamGroups(made[v], staff, twoVoices)
                vi[staff][v][b] = made[v]
            }
        }
    }

    private fun level(n: Int) = when (n) { 240, 360 -> 1; 120, 180 -> 2; 60 -> 3; else -> 0 }

    private fun beamGroups(list: List<PItem>, staff: Int, twoVoices: Boolean) {
        var cur = ArrayList<PItem>()
        fun flush() {
            if (cur.size >= 2) {
                val up = if (twoVoices) cur[0].voice == 0
                else cur.sumOf { (it.steps.min() + it.steps.max()) / 2.0 } / cur.size < MID_STEP[staff]
                val bm = Beam(cur.toList(), up)
                for (p in cur) { p.up = up; p.beam = bm }
            }
            cur = ArrayList()
        }
        for (p in list) {
            if (p.it.ps == null || level(p.it.notated) == 0) { flush(); continue }
            if (cur.isNotEmpty()) {
                val q = cur.last()
                if (q.it.rel / grid.beat != p.it.rel / grid.beat || q.it.tup != p.it.tup) flush()
            }
            cur.add(p)
        }
        flush()
    }

    // ------------------------------------------------------------------ šířky taktů
    private fun gap(dt: Int) = sp * (2.0f + 3.3f * sqrt(dt / 480f))

    private fun computeColumns() {
        for (b in 0 until nb) {
            val accAt = HashMap<Int, Int>()
            val rels = java.util.TreeSet<Int>()
            for (st in 0..1) for (v in 0..1) for (p in vi[st][v][b]!!) {
                rels.add(p.it.rel)
                val n = p.accs.count { it != 99 }
                if (n > 0) accAt[p.it.rel] = max(accAt[p.it.rel] ?: 0, n)
            }
            val r = rels.toIntArray()
            val pos = FloatArray(r.size)
            var x = sp * 1.0f
            for (i in r.indices) {
                if (i > 0) x += gap(r[i] - r[i - 1])
                x += sp * 1.1f * min(accAt[r[i]] ?: 0, 2)
                pos[i] = x
            }
            cols[b] = Cols(r, pos, x + gap(grid.bar - r.last()) * 0.9f)
        }
    }

    // ------------------------------------------------------------------ rozvržení do systémů a stránek
    private fun layout(): Int {
        val sig = abs(key.signature)
        var b = 0
        var page = 0
        var cy = MT + 62f
        var sysIdx = 0
        while (b < nb) {
            val first = sysIdx == 0
            val headerW = sp * (3.6f + sig * 1.05f + (if (first) 2.8f else 0.6f))
            val avail = PW - ML - MR - headerW
            var e = b
            var sum = cols[b]!!.width
            while (e + 1 < nb && (e - b + 1) < score.perLine && sum + cols[e + 1]!!.width <= avail * 1.6f) {
                e++; sum += cols[e]!!.width
            }
            var scale = avail / sum
            if (scale > 3f) scale = 3f
            // svislé rozměry
            var minR = 99; var maxR = -99; var minL = 99; var maxL = -99
            for (bb in b..e) {
                for (v in 0..1) {
                    for (p in vi[0][v][bb]!!) for (s in p.steps) { minR = min(minR, s); maxR = max(maxR, s) }
                    for (p in vi[1][v][bb]!!) for (s in p.steps) { minL = min(minL, s); maxL = max(maxL, s) }
                }
            }
            if (minR == 99) { minR = BOT_STEP[0]; maxR = TOP_STEP[0] }
            if (minL == 99) { minL = BOT_STEP[1]; maxL = TOP_STEP[1] }
            val aboveRH = max(3.2f, (maxR - TOP_STEP[0]) / 2f + 2.6f)
            val belowRH = max(0f, (BOT_STEP[0] - minR) / 2f)
            val aboveLH = max(0f, (maxL - TOP_STEP[1]) / 2f)
            val belowLH = max(0f, (BOT_STEP[1] - minL) / 2f)
            val gapSp = max(8f, belowRH + aboveLH + 5f)
            val h = (aboveRH + 4f + gapSp + 4f + max(belowLH + 2f, 2.5f) + 2f) * sp
            if (cy + h > PH - MB && sysIdx > 0 && cy > MT + 1f) {
                page++; cy = MT
            }
            val rhTop = cy + aboveRH * sp
            val lhTop = rhTop + (4f + gapSp) * sp
            val s = Sys(page, b..e, rhTop, lhTop, scale, first, headerW)
            var x = ML + headerW
            for (bb in b..e) {
                s.barLeft[bb] = x
                s.barW[bb] = cols[bb]!!.width * scale
                x += s.barW[bb]
            }
            systems.add(s)
            val idx = systems.size - 1
            for (bb in b..e) for (st in 0..1) for (v in 0..1) for (p in vi[st][v][bb]!!) {
                p.sys = idx
                val c = cols[bb]!!
                val i = c.rels.indexOf(p.it.rel)
                p.x = s.barLeft[bb] + c.pos[i] * scale
                p.barCenter = s.barLeft[bb] + s.barW[bb] / 2f
            }
            cy += h
            b = e + 1
            sysIdx++
        }
        return page + 1
    }

    /** vazby: položka -> další položka stejného hlasu */
    private fun link() {
        for (st in 0..1) for (v in 0..1) {
            val flat = ArrayList<PItem>()
            for (b in 0 until nb) flat.addAll(vi[st][v][b]!!)
            for (i in 0 until flat.size - 1) {
                val p = flat[i]
                if (p.it.tie && p.it.ps != null) {
                    val n = flat[i + 1]
                    if (n.it.ps != null && p.it.ps!!.any { it in n.it.ps!! }) {
                        p.tieTo = n
                        if (n.sys != p.sys) n.tieFromPrev = true
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ kreslení
    private val fillP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.BLACK }
    private val strokeP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.BLACK; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val textP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }

    private fun line(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, w: Float) {
        strokeP.strokeWidth = w
        strokeP.strokeCap = Paint.Cap.BUTT
        c.drawLine(x0, y0, x1, y1, strokeP)
    }

    fun draw(c: Canvas, page: Int) {
        c.drawColor(Color.WHITE)
        if (page == 0) {
            textP.typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            textP.textSize = 21f
            textP.textAlign = Paint.Align.CENTER
            c.drawText(score.title, PW / 2f, MT + 26f, textP)
            textP.textAlign = Paint.Align.LEFT
        }
        for ((idx, s) in systems.withIndex()) if (s.page == page) drawSystem(c, s, idx)
    }

    private fun yOf(staff: Int, top: Float, step: Int) = top + (TOP_STEP[staff] - step) * sp / 2f

    private fun drawSystem(c: Canvas, s: Sys, idx: Int) {
        val xL = ML
        val xR = PW - MR
        val tops = floatArrayOf(s.rhTop, s.lhTop)
        for (st in 0..1) for (i in 0..4) line(c, xL, tops[st] + i * sp, xR, tops[st] + i * sp, 0.09f * sp * 1.4f)
        // levá čára + složená závorka
        line(c, xL, tops[0], xL, tops[1] + 4 * sp, 0.18f * sp)
        drawBrace(c, xL - 0.7f * sp, tops[0], tops[1] + 4 * sp)

        // klíče, předznamenání, takt
        drawTreble(c, xL + 0.5f * sp, tops[0])
        drawBass(c, xL + 0.5f * sp, tops[1])
        val sig = key.signature
        var kx = xL + 3.7f * sp
        for (i in 0 until abs(sig)) {
            for (st in 0..1) {
                val step = if (sig > 0) SHARP_STEPS[st][i] else FLAT_STEPS[st][i]
                if (sig > 0) drawSharp(c, kx, yOf(st, tops[st], step)) else drawFlat(c, kx, yOf(st, tops[st], step))
            }
            kx += 1.05f * sp
        }
        if (s.first) {
            val tx = kx + 1.2f * sp
            for (st in 0..1) {
                textP.typeface = Typeface.DEFAULT_BOLD
                textP.textSize = 2.9f * sp
                textP.textAlign = Paint.Align.CENTER
                c.drawText("${grid.num}", tx, tops[st] + 1f * sp + 1.0f * sp, textP)
                c.drawText("${grid.den}", tx, tops[st] + 3f * sp + 1.0f * sp, textP)
                textP.textAlign = Paint.Align.LEFT
            }
            // ♩ = tempo
            val bx = s.barLeft[s.bars.first] + 0.2f * sp
            val by = tops[0] - 2.0f * sp
            c.save()
            c.rotate(-20f, bx, by)
            c.drawOval(RectF(bx - 0.55f * sp, by - 0.38f * sp, bx + 0.55f * sp, by + 0.38f * sp), fillP)
            c.restore()
            line(c, bx + 0.52f * sp, by - 0.1f * sp, bx + 0.52f * sp, by - 2.6f * sp, 0.12f * sp)
            textP.typeface = Typeface.DEFAULT
            textP.textSize = 8.5f
            c.drawText("= ${Math.round(score.bpm)}", bx + 1.3f * sp, by + 0.2f * sp, textP)
        }

        for (b in s.bars) {
            val left = s.barLeft[b]
            val right = left + s.barW[b]
            // číslo taktu a značky tempa
            if (b > 0) {
                textP.typeface = Typeface.DEFAULT
                textP.textSize = 7f
                c.drawText("${b + 1}", left + 0.2f * sp, tops[0] - 1.4f * sp, textP)
            }
            marks[b]?.let {
                textP.typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
                textP.textSize = 9f
                c.drawText(it, left + 2.4f * sp, tops[0] - 1.6f * sp, textP)
            }
            // taktová čára
            for (st in 0..1) {
                if (b == nb - 1) {
                    line(c, right - 0.75f * sp, tops[st], right - 0.75f * sp, tops[st] + 4 * sp, 0.14f * sp)
                    line(c, right - 0.25f * sp, tops[st], right - 0.25f * sp, tops[st] + 4 * sp, 0.5f * sp)
                } else line(c, right, tops[st], right, tops[st] + 4 * sp, 0.14f * sp)
            }
        }

        // položky
        for (b in s.bars) for (st in 0..1) for (v in 0..1) {
            val list = vi[st][v][b]!!
            for (p in list) drawItem(c, p, tops[st])
            drawTuplets(c, list, tops[st])
        }
        // vazby
        for (b in s.bars) for (st in 0..1) for (v in 0..1) for (p in vi[st][v][b]!!) {
            if (p.tieTo != null) drawTie(c, p, p.tieTo!!, tops[st], s)
            if (p.tieFromPrev) drawTieIn(c, p, tops[st], s)
        }
    }

    // ---------- klíče a značky
    private fun drawBrace(c: Canvas, x: Float, top: Float, bot: Float) {
        val mid = (top + bot) / 2f
        val w = 1.9f * sp
        val p = Path()
        p.moveTo(x, top)
        p.cubicTo(x - w, top + (mid - top) * 0.3f, x + 0.3f * sp, mid - (mid - top) * 0.35f, x - w, mid)
        p.cubicTo(x + 0.3f * sp, mid + (bot - mid) * 0.35f, x - w, bot - (bot - mid) * 0.3f, x, bot)
        strokeP.strokeWidth = 0.38f * sp
        strokeP.strokeCap = Paint.Cap.ROUND
        c.drawPath(p, strokeP)
    }

    private fun drawTreble(c: Canvas, x0: Float, top: Float) {
        val x = x0 + 1.4f * sp
        val g = top + 3f * sp
        fun X(v: Float) = x + v * sp
        fun Y(v: Float) = g + v * sp
        val body = Path()
        body.moveTo(X(0.05f), Y(-3.5f))
        body.cubicTo(X(0.9f), Y(-4.4f), X(1.5f), Y(-3.2f), X(1.2f), Y(-2.4f))
        body.cubicTo(X(0.9f), Y(-1.5f), X(-0.9f), Y(-0.8f), X(-1.0f), Y(0.4f))
        body.cubicTo(X(-1.1f), Y(1.6f), X(0.5f), Y(1.9f), X(0.9f), Y(1.0f))
        body.cubicTo(X(1.3f), Y(0.2f), X(0.8f), Y(-0.7f), X(0f), Y(-0.6f))
        body.cubicTo(X(-0.6f), Y(-0.5f), X(-0.7f), Y(0.3f), X(-0.3f), Y(0.6f))
        val vert = Path()
        vert.moveTo(X(0.3f), Y(-3.6f))
        vert.lineTo(X(0.3f), Y(2.7f))
        vert.cubicTo(X(0.3f), Y(3.5f), X(-0.4f), Y(3.8f), X(-0.75f), Y(3.2f))
        strokeP.strokeWidth = 0.2f * sp
        strokeP.strokeCap = Paint.Cap.ROUND
        c.drawPath(body, strokeP)
        c.drawPath(vert, strokeP)
        c.drawCircle(X(-0.75f), Y(3.2f), 0.27f * sp, fillP)
    }

    private fun drawBass(c: Canvas, x0: Float, top: Float) {
        val x = x0 + 1.4f * sp
        val f = top + 1f * sp
        fun X(v: Float) = x + v * sp
        fun Y(v: Float) = f + v * sp
        val p = Path()
        p.moveTo(X(-0.9f), Y(0f))
        p.cubicTo(X(-0.9f), Y(-1.0f), X(0.7f), Y(-1.1f), X(1.2f), Y(0f))
        p.cubicTo(X(1.6f), Y(1.0f), X(0.9f), Y(2.2f), X(-1.4f), Y(2.9f))
        strokeP.strokeWidth = 0.26f * sp
        strokeP.strokeCap = Paint.Cap.ROUND
        c.drawPath(p, strokeP)
        c.drawCircle(X(-0.95f), Y(0f), 0.46f * sp, fillP)
        c.drawCircle(X(1.95f), Y(-0.5f), 0.2f * sp, fillP)
        c.drawCircle(X(1.95f), Y(0.5f), 0.2f * sp, fillP)
    }

    private fun drawSharp(c: Canvas, x: Float, y: Float) {
        line(c, x - 0.2f * sp, y - 1.35f * sp, x - 0.2f * sp, y + 1.5f * sp, 0.1f * sp)
        line(c, x + 0.2f * sp, y - 1.5f * sp, x + 0.2f * sp, y + 1.35f * sp, 0.1f * sp)
        line(c, x - 0.5f * sp, y - 0.25f * sp, x + 0.5f * sp, y - 0.6f * sp, 0.28f * sp)
        line(c, x - 0.5f * sp, y + 0.6f * sp, x + 0.5f * sp, y + 0.25f * sp, 0.28f * sp)
    }

    private fun drawFlat(c: Canvas, x: Float, y: Float) {
        val x0 = x - 0.3f * sp
        line(c, x0, y - 2.3f * sp, x0, y + 0.55f * sp, 0.1f * sp)
        val p = Path()
        p.moveTo(x0, y + 0.6f * sp)
        p.cubicTo(x0 + 0.3f * sp, y + 0.3f * sp, x0 + 0.95f * sp, y - 0.1f * sp, x0 + 0.8f * sp, y - 0.5f * sp)
        p.cubicTo(x0 + 0.65f * sp, y - 0.95f * sp, x0 + 0.25f * sp, y - 0.8f * sp, x0, y - 0.35f * sp)
        strokeP.strokeWidth = 0.17f * sp
        strokeP.strokeCap = Paint.Cap.ROUND
        c.drawPath(p, strokeP)
    }

    private fun drawNatural(c: Canvas, x: Float, y: Float) {
        line(c, x - 0.3f * sp, y - 1.4f * sp, x - 0.3f * sp, y + 0.65f * sp, 0.1f * sp)
        line(c, x + 0.3f * sp, y - 0.65f * sp, x + 0.3f * sp, y + 1.4f * sp, 0.1f * sp)
        line(c, x - 0.3f * sp, y - 0.35f * sp, x + 0.3f * sp, y - 0.6f * sp, 0.26f * sp)
        line(c, x - 0.3f * sp, y + 0.6f * sp, x + 0.3f * sp, y + 0.35f * sp, 0.26f * sp)
    }

    // ---------- položky
    private fun drawItem(c: Canvas, p: PItem, top: Float) {
        val itm = p.it
        if (itm.spacer) return
        if (itm.ps == null) { drawRest(c, p, top); return }
        val st = p.staff
        val flips = p.flips()
        val ys = FloatArray(p.steps.size) { yOf(st, top, p.steps[it]) }
        val yHi = ys.min()     // nejvyšší hlava (nejmenší y)
        val yLo = ys.max()
        val n = itm.notated

        // ledger lines
        for ((i, step) in p.steps.withIndex()) {
            val hx = p.x + (if (flips[i]) (if (p.up) HW else -HW) else 0f)
            var s = TOP_STEP[st] + 2
            while (s <= step) { val y = yOf(st, top, s); line(c, hx - HW * 0.85f, y, hx + HW * 0.85f, y, 0.1f * sp * 1.4f); s += 2 }
            s = BOT_STEP[st] - 2
            while (s >= step) { val y = yOf(st, top, s); line(c, hx - HW * 0.85f, y, hx + HW * 0.85f, y, 0.1f * sp * 1.4f); s -= 2 }
        }
        // hlavy
        for (i in p.steps.indices) {
            val hx = p.x + (if (flips[i]) (if (p.up) HW else -HW) else 0f)
            drawHead(c, hx, ys[i], n)
            if (n == 1440 || n == 720 || n == 360 || n == 180) {
                val onLine = (TOP_STEP[st] - p.steps[i]) % 2 == 0
                c.drawCircle(hx + HW * 0.5f + 0.7f * sp, ys[i] - (if (onLine) 0.5f * sp else 0f), 0.2f * sp, fillP)
            }
        }
        // posuvky
        val accItems = p.accs.indices.filter { p.accs[it] != 99 }.sortedByDescending { p.steps[it] }
        if (accItems.isNotEmpty()) {
            val colSteps = ArrayList<MutableList<Int>>()
            val minDx = if (flips.any { it } && !p.up) -HW else 0f
            for (i in accItems) {
                var col = 0
                while (true) {
                    if (col >= colSteps.size) colSteps.add(ArrayList())
                    if (colSteps[col].all { abs(it - p.steps[i]) >= 6 }) break
                    col++
                }
                colSteps[col].add(p.steps[i])
                val ax = p.x + minDx - HW * 0.5f - 0.9f * sp - col * 1.1f * sp
                when (p.accs[i]) {
                    1 -> drawSharp(c, ax, ys[i])
                    -1 -> drawFlat(c, ax, ys[i])
                    else -> drawNatural(c, ax, ys[i])
                }
            }
        }
        // linka
        if (n < 1920) {
            val sx = stemX(p)
            val tip = tipY(p, top)
            val from = if (p.up) yLo else yHi
            line(c, sx, from, sx, tip, 0.13f * sp)
            if (p.beam == null) {
                val fl = level(n)
                for (k in 0 until fl) drawFlag(c, sx, tip + (if (p.up) 1 else -1) * k * 0.85f * sp, p.up)
            }
        }
        // korouhvička
        if (itm.fermata) {
            val fy = min(top - 1.6f * sp, (if (p.up) yHi - 4.8f * sp else yHi - 1.8f * sp))
            val pa = Path()
            pa.addArc(RectF(p.x - 1.2f * sp, fy - 1.2f * sp, p.x + 1.2f * sp, fy + 1.2f * sp), 180f, 180f)
            strokeP.strokeWidth = 0.18f * sp
            c.drawPath(pa, strokeP)
            c.drawCircle(p.x, fy - 0.15f * sp, 0.22f * sp, fillP)
        }
        // trámec se kreslí jen u první položky skupiny
        val bm = p.beam
        if (bm != null && bm.items[0] === p) drawBeam(c, bm, top)
    }

    private fun stemX(p: PItem) = if (p.up) p.x + HW / 2f - 0.065f * sp else p.x - HW / 2f + 0.065f * sp

    private fun defaultTip(p: PItem, top: Float): Float {
        val st = p.staff
        val ys = p.steps.map { yOf(st, top, it) }
        val mid = top + 2f * sp
        val len = if (p.beam != null) 3.3f else 3.5f
        return if (p.up) min(ys.min() - len * sp, mid).let { if (it > ys.min() - 2.5f * sp) ys.min() - 2.5f * sp else it }
        else max(ys.max() + len * sp, mid).let { if (it < ys.max() + 2.5f * sp) ys.max() + 2.5f * sp else it }
    }

    private class BeamGeo(val xs: FloatArray, val ya: Float, val yb: Float, val shift: Float) {
        fun at(x: Float): Float {
            val d = xs.last() - xs.first()
            val t = if (d == 0f) 0f else (x - xs.first()) / d
            return ya + (yb - ya) * t + shift
        }
    }

    private fun beamGeo(bm: Beam, top: Float): BeamGeo {
        val xs = FloatArray(bm.items.size) { stemX(bm.items[it]) }
        val tips = FloatArray(bm.items.size) { defaultTip(bm.items[it], top) }
        val ya = tips.first()
        var yb = tips.last()
        yb = ya + (yb - ya).coerceIn(-1.5f * sp, 1.5f * sp)
        val tmp = BeamGeo(xs, ya, yb, 0f)
        var shift = 0f
        for (i in xs.indices) {
            val diff = tips[i] - tmp.at(xs[i])
            if (bm.up) shift = min(shift, diff) else shift = max(shift, diff)
        }
        return BeamGeo(xs, ya, yb, shift)
    }

    private fun tipY(p: PItem, top: Float): Float {
        val bm = p.beam ?: return defaultTip(p, top)
        return beamGeo(bm, top).at(stemX(p))
    }

    private fun drawBeam(c: Canvas, bm: Beam, top: Float) {
        val g = beamGeo(bm, top)
        val dir = if (bm.up) 1f else -1f
        val th = 0.5f * sp
        val sw = 0.065f * sp
        fun poly(xa: Float, xb: Float, k: Float) {
            val p = Path()
            p.moveTo(xa, g.at(xa) + dir * k)
            p.lineTo(xb, g.at(xb) + dir * k)
            p.lineTo(xb, g.at(xb) + dir * (k + th))
            p.lineTo(xa, g.at(xa) + dir * (k + th))
            p.close()
            c.drawPath(p, fillP)
        }
        poly(g.xs.first() - sw, g.xs.last() + sw, 0f)
        for (lvl in 2..3) {
            val k = (lvl - 1) * 0.8f * sp
            for (i in bm.items.indices) {
                val me = level(bm.items[i].it.notated) >= lvl
                if (!me) continue
                val nextOk = i + 1 < bm.items.size && level(bm.items[i + 1].it.notated) >= lvl
                val prevOk = i > 0 && level(bm.items[i - 1].it.notated) >= lvl
                if (nextOk) poly(g.xs[i] - sw, g.xs[i + 1] + sw, k)
                else if (!prevOk) {
                    if (i > 0) poly(g.xs[i] - 1.2f * sp, g.xs[i] + sw, k) else poly(g.xs[i] - sw, g.xs[i] + 1.2f * sp, k)
                }
            }
        }
        // linky trámcem prodloužené
    }

    private fun drawHead(c: Canvas, x: Float, y: Float, n: Int) {
        if (n >= 1920 && n < 1921) {          // celá nota
            strokeP.strokeWidth = 0.3f * sp
            c.drawOval(RectF(x - HW * 0.62f, y - 0.4f * sp, x + HW * 0.62f, y + 0.4f * sp), strokeP.also { it.strokeCap = Paint.Cap.BUTT })
            return
        }
        c.save()
        c.rotate(-20f, x, y)
        val r = RectF(x - HW * 0.5f, y - 0.5f * sp * 0.82f, x + HW * 0.5f, y + 0.5f * sp * 0.82f)
        if (n >= 960) {   // půlová (prázdná)
            strokeP.strokeWidth = 0.22f * sp
            val r2 = RectF(r.left + 0.11f * sp, r.top + 0.11f * sp, r.right - 0.11f * sp, r.bottom - 0.11f * sp)
            c.drawOval(r2, strokeP.also { it.strokeCap = Paint.Cap.BUTT })
        } else c.drawOval(r, fillP)
        c.restore()
    }

    private fun drawFlag(c: Canvas, x: Float, y: Float, up: Boolean) {
        val d = if (up) 1f else -1f
        val p = Path()
        p.moveTo(x, y)
        p.cubicTo(x + 0.2f * sp, y + d * 1.0f * sp, x + 1.5f * sp, y + d * 1.3f * sp, x + 1.0f * sp, y + d * 2.9f * sp)
        strokeP.strokeWidth = 0.3f * sp
        strokeP.strokeCap = Paint.Cap.ROUND
        c.drawPath(p, strokeP)
    }

    private fun drawRest(c: Canvas, p: PItem, top: Float) {
        val it = p.it
        val x = if (it.fullBar) p.barCenter else p.x
        val mid = top + 2f * sp
        val n = it.notated
        when {
            it.fullBar || n >= 1440 -> c.drawRect(x - 0.7f * sp, top + 1f * sp, x + 0.7f * sp, top + 1.5f * sp, fillP)
            n >= 720 && n <= 960 -> c.drawRect(x - 0.7f * sp, top + 1.5f * sp, x + 0.7f * sp, top + 2f * sp, fillP)
            n == 480 || n == 720 -> {
                val q = Path()
                q.moveTo(x + 0.35f * sp, mid - 1.5f * sp)
                q.lineTo(x - 0.35f * sp, mid - 0.55f * sp)
                q.lineTo(x + 0.45f * sp, mid + 0.35f * sp)
                q.cubicTo(x - 0.5f * sp, mid + 0.6f * sp, x - 0.4f * sp, mid + 1.4f * sp, x + 0.4f * sp, mid + 1.5f * sp)
                strokeP.strokeWidth = 0.3f * sp
                strokeP.strokeCap = Paint.Cap.ROUND
                c.drawPath(q, strokeP)
            }
            else -> {
                val k = max(1, level(n))
                line(c, x + 0.45f * sp, mid - 0.9f * sp, x - 0.2f * sp - (k - 1) * 0.3f * sp, mid + 0.9f * sp + (k - 1) * 0.9f * sp, 0.16f * sp)
                for (j in 0 until k) c.drawCircle(x - 0.1f * sp - j * 0.3f * sp, mid - 0.65f * sp + j * 0.9f * sp, 0.34f * sp, fillP)
            }
        }
        if (n == 1440 || n == 720 || n == 360 || n == 180) c.drawCircle(x + 1.2f * sp, mid - 0.4f * sp, 0.2f * sp, fillP)
    }

    private fun drawTuplets(c: Canvas, list: List<PItem>, top: Float) {
        var i = 0
        while (i < list.size) {
            val t = list[i].it.tup
            if (t == 0) { i++; continue }
            var j = i
            while (j + 1 < list.size && list[j + 1].it.tup == t) j++
            val grp = list.subList(i, j + 1)
            val notes = grp.filter { it.it.ps != null }
            val up = notes.firstOrNull()?.up ?: true
            val x0 = grp.first().x
            val x1 = grp.last().x
            val allBeamed = notes.size == grp.size && grp.all { it.beam != null && it.beam === grp[0].beam }
            val ys = ArrayList<Float>()
            for (p in notes) {
                ys.add(if (p.it.notated < 1920) tipY(p, top) else p.steps.map { yOf(p.staff, top, it) }.let { if (up) it.min() else it.max() })
            }
            val base = if (up) min(ys.minOrNull() ?: top, top) - 0.9f * sp else max(ys.maxOrNull() ?: (top + 4 * sp), top + 4 * sp) + 0.9f * sp
            val cx = (x0 + x1) / 2f
            textP.typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
            textP.textSize = 8f
            textP.textAlign = Paint.Align.CENTER
            c.drawText("3", cx, base + (if (up) -0.1f else 0.9f) * sp + (if (up) 0f else 0.3f * sp), textP)
            textP.textAlign = Paint.Align.LEFT
            if (!allBeamed) {
                val h = if (up) 0.8f * sp else -0.8f * sp
                val yb = base
                line(c, x0, yb, cx - 1.1f * sp, yb, 0.1f * sp)
                line(c, cx + 1.1f * sp, yb, x1, yb, 0.1f * sp)
                line(c, x0, yb, x0, yb + h, 0.1f * sp)
                line(c, x1, yb, x1, yb + h, 0.1f * sp)
            }
            i = j + 1
        }
    }

    private fun headY(p: PItem, top: Float, i: Int) = yOf(p.staff, top, p.steps[i])

    private fun tieArc(c: Canvas, xa: Float, xb: Float, y: Float, below: Boolean) {
        val d = if (below) 1f else -1f
        val p = Path()
        val y0 = y + d * 0.6f * sp
        p.moveTo(xa, y0)
        p.cubicTo(xa + (xb - xa) * 0.25f, y0 + d * 0.95f * sp, xa + (xb - xa) * 0.75f, y0 + d * 0.95f * sp, xb, y0)
        strokeP.strokeWidth = 0.2f * sp
        strokeP.strokeCap = Paint.Cap.ROUND
        c.drawPath(p, strokeP)
    }

    private fun belowFor(p: PItem, i: Int): Boolean =
        if (p.steps.size == 1) p.up else p.steps[i] < (p.steps.min() + p.steps.max()) / 2f || (p.steps[i] == p.steps.min())

    private fun drawTie(c: Canvas, p: PItem, n: PItem, top: Float, s: Sys) {
        val flips = p.flips()
        for ((i, pc) in p.it.ps!!.withIndex()) {
            if (pc !in n.it.ps!!) continue
            val y = headY(p, top, i)
            val xa = p.x + (if (flips[i] && p.up) HW else 0f) + HW * 0.55f
            val xb = if (n.sys == p.sys) n.x - HW * 0.55f else PW - MR - 0.4f * sp
            if (xb - xa > 0.8f * sp) tieArc(c, xa, xb, y, belowFor(p, i))
        }
    }

    private fun drawTieIn(c: Canvas, p: PItem, top: Float, s: Sys) {
        val prev = findPrevTie(p) ?: return
        for ((i, pc) in p.it.ps!!.withIndex()) {
            if (pc !in prev.it.ps!!) continue
            val y = headY(p, top, i)
            val xa = s.barLeft[s.bars.first] - 0.3f * sp
            val xb = p.x - HW * 0.55f
            if (xb - xa > 0.8f * sp) tieArc(c, xa, xb, y, belowFor(p, i))
        }
    }

    private fun findPrevTie(p: PItem): PItem? {
        for (b in p.bar downTo max(0, p.bar - 1)) for (it in vi[p.staff][p.voice][b]!!) if (it.tieTo === p) return it
        return null
    }
}
