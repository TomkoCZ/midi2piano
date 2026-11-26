package cz.midi2piano

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Video "padající noty" – 1:1 port vzhledu ze skriptu (1280×720, úvod 5 s, částice, klaviatura). */
class Scene(R: List<Note>, L: List<Note>, val title: String, names: String, sharp: Boolean, private val endSec: Double) {
    companion object {
        const val W = 1280f
        const val H = 720f
        const val KH = 130f
        const val HIT = H - KH
        const val LOOK = 2.2f
        const val PPS = HIT / LOOK
        const val T0 = 5.0           // úvod před hudbou
        const val LIFE = 0.9f        // život částic
        private val COL = arrayOf(
            intArrayOf(Color.rgb(90, 170, 255), Color.rgb(170, 215, 255)),
            intArrayOf(Color.rgb(255, 165, 60), Color.rgb(255, 215, 150)),
        )
        private val DK = intArrayOf(Color.rgb(25, 70, 150), Color.rgb(150, 70, 10))
        private val BG = Color.rgb(16, 18, 28)
    }

    private class VN(val s: Double, val e: Double, val p: Int, val h: Int)

    private val notes: List<VN> = (R.map { VN(it.s, it.e, it.p, 0) } + L.map { VN(it.s, it.e, it.p, 1) })
        .sortedWith(compareBy({ it.s }, { it.e }, { it.p }, { it.h }))
    private val starts = DoubleArray(notes.size) { notes[it].s }
    private val maxDur = notes.maxOfOrNull { it.e - it.s } ?: 1.0
    private val lo: Int
    private val hi: Int
    private val x0 = FloatArray(129)
    private val x1 = FloatArray(129)
    private val kw: Float
    private val nlist: Array<String>? = noteNames(names, sharp)
    private val part: FloatArray        // 12 částic × (ox, vx, vy, r) na notu
    private fun isb(p: Int) = (1354 shr (p % 12)) and 1 == 1

    init {
        val allp = notes.map { it.p }
        var l = max(12, allp.min() / 12 * 12)
        var h = min(120, (allp.max() / 12 + 1) * 12)
        while (h - l < 36) {
            if (h < 108) h += 12
            if (h - l < 36 && l > 12) l -= 12
            if (h >= 108 && l <= 12) break
        }
        lo = l; hi = h
        val wk = (lo..hi).filter { !isb(it) }
        kw = W / wk.size
        for ((i, p) in wk.withIndex()) { x0[p] = i * kw; x1[p] = (i + 1) * kw }
        for (p in lo..hi) if (isb(p)) {
            val c = x1[p - 1]
            val bw = kw * 0.62f
            x0[p] = c - bw / 2; x1[p] = c + bw / 2
        }
        val rng = java.util.Random(7)
        part = FloatArray(notes.size * 48)
        for (i in notes.indices) for (j in 0 until 12) {
            val o = i * 48 + j * 4
            part[o] = (rng.nextFloat() * 18f - 9f)
            part[o + 1] = (rng.nextFloat() * 40f - 20f)
            part[o + 2] = 70f + rng.nextFloat() * 160f
            part[o + 3] = 2f + rng.nextFloat() * 2.5f
        }
    }

    private val bold = Typeface.DEFAULT_BOLD
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f }
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = bold }
    private val rect = RectF()
    private val gradient = Paint().apply {
        shader = LinearGradient(0f, 0f, 0f, H, Color.rgb(16, 18, 28), Color.rgb(26, 28, 48), Shader.TileMode.CLAMP)
    }
    private var titleSize = 110f
    private var titleW = 0f

    init {
        var sz = 110f
        while (sz > 30f) {
            pText.textSize = sz
            if (pText.measureText(title) <= W * 0.85f) break
            sz -= 6f
        }
        titleSize = sz
        pText.textSize = sz
        titleW = pText.measureText(title)
    }

    private fun ease(x: Float): Float { val v = max(0f, min(1f, x)); return 1f - (1f - v).pow(3) }
    private fun a(alpha: Float, c: Int) = Color.argb((alpha * 255).toInt().coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))

    private fun text(c: Canvas, s: String, x: Float, topY: Float, size: Float, color: Int, center: Boolean = false) {
        pText.textSize = size
        pText.color = color
        pText.textAlign = if (center) Paint.Align.CENTER else Paint.Align.LEFT
        c.drawText(s, x, topY - pText.fontMetrics.ascent, pText)
    }

    /** Vykreslí snímek v čase t (s, včetně úvodu) při rychlosti sp (1, 0.55, 0.25…). Plátno = 1280×720. */
    fun draw(c: Canvas, t: Double, sp: Double) {
        val spf = sp.toFloat()
        val tm = ((t - T0) * sp).toFloat()
        c.drawRect(0f, 0f, W, H, gradient)
        pStroke.color = Color.rgb(40, 44, 62)
        for (p in lo..hi) if (p % 12 == 0) c.drawLine(x0[p], 0f, x0[p], HIT, pStroke)

        val ka = ease(((t - 3.7) / 0.6).toFloat())
        if (t < 3.7) {
            val al = max(0f, min(1f, (t / 0.6).toFloat()))
            val pp = max(0f, ((t - 3.0) / 0.7).toFloat()).pow(3)
            val off = pp * (W / 2 + titleW / 2 + 60f)
            val cc = (235 * al).toInt()
            text(c, title, (W - titleW) / 2 - off, H / 2 - titleSize * 0.95f, titleSize, Color.rgb(cc, cc, min(255, cc + 10)))
            pText.textSize = 30f
            val sub = "Piano tutorial"
            val sw = pText.measureText(sub)
            val c2 = (150 * al).toInt()
            text(c, sub, (W - sw) / 2 - off, H / 2 + 30f, 30f, Color.rgb(c2, c2, min(255, c2 + 10)))
        }
        if (ka <= 0f) return

        val act = IntArray(129) { -1 }
        val i0 = max(0, lowerBound(tm - maxDur - LIFE * spf) - 1)
        var iEnd = notes.size
        // pády not
        for (i in i0 until notes.size) {
            val n = notes[i]
            if (n.s > tm + LOOK) { iEnd = i; break }
            if (n.e <= tm - LIFE * spf) continue
            if (n.s <= tm && tm < n.e) act[n.p] = n.h
            if (n.e <= tm) continue
            val black = isb(n.p)
            var xa = x0[n.p]; var xb = x1[n.p]
            if (black) { xa += 1; xb -= 1 } else { xa += 2; xb -= 2 }
            var yb = HIT - (n.s.toFloat() - tm) * PPS
            var yt = HIT - (n.e.toFloat() - tm) * PPS + 3
            yb = min(yb, HIT) - 1
            yt = max(yt, -5f)
            if (yb - yt < 6) yt = yb - 6
            rect.set(xa, yt, xb, yb)
            pFill.color = a(ka, COL[n.h][0]); c.drawRoundRect(rect, 5f, 5f, pFill)
            pStroke.color = a(ka, DK[n.h]); c.drawRoundRect(rect, 5f, 5f, pStroke)
            rect.set(xa, yb - 5, xb, yb)
            pFill.color = a(ka, DK[n.h]); c.drawRoundRect(rect, 3f, 3f, pFill)
            if (nlist != null && yb - yt >= 26) {
                pText.textSize = if (black) max(8f, kw * 0.40f) else max(9f, kw * 0.47f)
                pText.color = a(ka, Color.rgb(10, 20, 45))
                pText.textAlign = Paint.Align.CENTER
                c.drawText(nlist[n.p % 12], (xa + xb) / 2, yb - 17 - (pText.fontMetrics.ascent + pText.fontMetrics.descent) / 2, pText)
            }
        }
        // částice
        for (i in i0 until iEnd) {
            val n = notes[i]
            val age0 = (tm - n.s).toFloat()
            if (age0 < 0 || age0 >= LIFE * spf) continue
            val age = age0 / spf
            val al = (1 - age / LIFE).pow(0.9f) * ka
            val cx = (x0[n.p] + x1[n.p]) / 2
            val base = COL[n.h][1]
            pFill.color = a(al, Color.rgb(min(255, Color.red(base) + 40), min(255, Color.green(base) + 40), min(255, Color.blue(base) + 40)))
            for (j in 0 until 12) {
                val o = i * 48 + j * 4
                val px = cx + part[o] + part[o + 1] * age
                val py = HIT - part[o + 2] * age * (1 - 0.35f * age) - 2
                c.drawCircle(px, py, part[o + 3], pFill)
            }
        }
        // klaviatura – vyjíždí zdola
        val oy = (1 - ka) * KH
        c.save()
        c.clipRect(0f, HIT + oy, W, H)
        c.translate(0f, oy)
        drawKeys(c, act)
        c.restore()
        pFill.color = Color.rgb(200, 60, 70)
        c.drawRect(0f, HIT - 3 + oy, W, HIT + oy, pFill)
        val cc = (235 * ka).toInt()
        text(c, title, 24f, 16f, 22f, Color.rgb(cc, cc, cc))
        pFill.color = Color.rgb(255, 190, 60)
        c.drawRect(0f, 0f, W * max(0f, min(1f, (tm / endSec).toFloat())), 4f, pFill)
    }

    private fun lowerBound(v: Double): Int {
        var l = 0; var r = starts.size
        while (l < r) { val m = (l + r) ushr 1; if (starts[m] < v) l = m + 1 else r = m }
        return l
    }

    private fun drawKeys(c: Canvas, act: IntArray) {
        for (p in lo..hi) if (!isb(p)) {
            pFill.color = if (act[p] >= 0) COL[act[p]][1] else Color.rgb(245, 245, 245)
            c.drawRect(x0[p] + 1, HIT, x1[p] - 1, H, pFill)
            if (p % 12 == 0) {
                pText.textSize = 16f
                pText.color = Color.rgb(90, 90, 90)
                pText.textAlign = Paint.Align.LEFT
                c.drawText("C${p / 12 - 1}", x0[p] + kw / 2 - 8, H - 22 - pText.fontMetrics.ascent, pText)
            }
        }
        for (p in lo..hi) if (isb(p)) {
            pFill.color = if (act[p] >= 0) COL[act[p]][0] else Color.rgb(25, 25, 30)
            c.drawRect(x0[p], HIT, x1[p], HIT + KH * 0.62f, pFill)
        }
    }
}
