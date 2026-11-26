package cz.midi2piano

/** Nota v ticcích (480 / čtvrťovou). */
data class Note(val s: Double, val e: Double, val p: Int)

class MidiData(
    val tracks: List<List<Note>>,
    val bpm: Double,
    /** (tick480, bpm) */
    val tmap: List<Pair<Double, Double>>,
    val ts: Pair<Int, Int>,
)

/** Vlastní parser SMF – port funkce read_midi() ze skriptu (mido). */
object MidiParser {
    fun parse(data: ByteArray): MidiData {
        var pos = 0
        val b = data
        fun u8(): Int = b[pos++].toInt() and 0xFF
        fun u16(): Int { val h = u8(); return (h shl 8) or u8() }
        fun u32(): Int { val h = u16(); return (h shl 16) or u16() }
        fun id4(): String { val s = String(b, pos, 4, Charsets.ISO_8859_1); pos += 4; return s }
        fun vlq(): Int {
            var v = 0
            while (true) {
                val c = u8()
                v = (v shl 7) or (c and 0x7F)
                if (c and 0x80 == 0) break
            }
            return v
        }

        // případný RIFF obal (.rmi) – najdi MThd
        var start = 0
        while (start + 4 <= b.size && String(b, start, 4, Charsets.ISO_8859_1) != "MThd") start++
        if (start + 14 > b.size) throw IllegalArgumentException("Soubor není platné MIDI.")
        pos = start + 4
        val hlen = u32()
        u16() // format
        u16() // počet stop
        val div = u16()
        pos = start + 8 + hlen
        val tpb = if (div and 0x8000 != 0 || div == 0) 480 else div
        val k = 480.0 / tpb

        val tracks = ArrayList<List<Note>>()
        val tempos = ArrayList<Pair<Long, Int>>()
        val tsigs = ArrayList<Triple<Long, Int, Int>>()
        var endTick = 0L

        while (pos + 8 <= b.size) {
            val id = id4()
            val len = u32()
            val end = minOf(b.size, pos + maxOf(0, len))
            if (id != "MTrk") { pos = end; continue }
            var t = 0L
            var run = 0
            val on = HashMap<Int, Long>()
            val out = ArrayList<Note>()
            while (pos < end) {
                t += vlq()
                if (pos >= end) break
                var st = b[pos].toInt() and 0xFF
                if (st < 0x80) st = run else { pos++; if (st < 0xF0) run = st }
                when {
                    st == 0xFF -> {
                        val type = u8()
                        val l = vlq()
                        val p0 = pos
                        if (type == 0x51 && l == 3 && p0 + 3 <= b.size) {
                            tempos.add(t to (((b[p0].toInt() and 0xFF) shl 16) or ((b[p0 + 1].toInt() and 0xFF) shl 8) or (b[p0 + 2].toInt() and 0xFF)))
                        } else if (type == 0x58 && l >= 2 && p0 + 2 <= b.size) {
                            tsigs.add(Triple(t, b[p0].toInt() and 0xFF, 1 shl (b[p0 + 1].toInt() and 0x1F)))
                        }
                        pos = p0 + l
                    }
                    st == 0xF0 || st == 0xF7 -> { val l = vlq(); pos += l }
                    else -> {
                        val hi = st and 0xF0
                        val ch = st and 0x0F
                        val d1 = u8()
                        val d2 = if (hi == 0xC0 || hi == 0xD0) 0 else u8()
                        if (ch != 9 && (hi == 0x90 || hi == 0x80)) {
                            val key = ch * 128 + d1
                            if (hi == 0x90 && d2 > 0) {
                                on[key]?.let { out.add(Note(it * k, t * k, d1)) }
                                on[key] = t
                            } else {
                                on.remove(key)?.let { out.add(Note(it * k, t * k, d1)) }
                            }
                        }
                    }
                }
            }
            for ((key, s) in on) out.add(Note(s * k, t * k, key % 128))
            endTick = maxOf(endTick, t)
            if (out.isNotEmpty()) tracks.add(out)
            pos = end
        }
        if (tracks.isEmpty()) throw IllegalArgumentException("V MIDI nejsou žádné noty (kromě bicích).")

        tempos.sortWith(compareBy({ it.first }, { it.second }))
        val base: Int = if (tempos.isNotEmpty()) {
            val w = LinkedHashMap<Int, Long>()
            for ((i, tt) in tempos.withIndex()) {
                val nxt = if (i + 1 < tempos.size) tempos[i + 1].first else maxOf(endTick, tt.first + 1)
                w[tt.second] = (w[tt.second] ?: 0L) + maxOf(1L, nxt - tt.first)
            }
            w.entries.maxByOrNull { it.value }!!.key
        } else 500000
        val bpm = 60e6 / base
        val tmap = tempos.map { (tt, tm) -> tt * k to 60e6 / tm }
        val ts = if (tsigs.isNotEmpty()) tsigs[0].second to tsigs[0].third else 4 to 4
        return MidiData(tracks, bpm, tmap, ts)
    }
}
