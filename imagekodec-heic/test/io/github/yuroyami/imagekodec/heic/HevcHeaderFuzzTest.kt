package io.github.yuroyami.imagekodec.heic

import io.github.yuroyami.imagekodec.AnnexB
import io.github.yuroyami.imagekodec.FuzzSettings
import io.github.yuroyami.imagekodec.FuzzShard
import io.github.yuroyami.imagekodec.HeifBuilder
import io.github.yuroyami.imagekodec.ImageDecodeException
import io.github.yuroyami.imagekodec.ImageKodec
import io.github.yuroyami.imagekodec.withDeadline
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Hostile headers for the HEVC decoder (#147). Byte mutation rarely reaches a parameter set, so this
 * test rewrites one syntax element of an SPS, PPS or slice segment header at a time, from the
 * positions the parser's trace records: an Exp-Golomb element gets boundary values written back as
 * Exp-Golomb codes, any other element other bit patterns of its own length. Every parameter set is
 * also cut at every byte, and a corpus of malformed HEIC files goes through the facade. Each case
 * decodes or throws ImageDecodeException, within a deadline, on every target.
 */
class HevcHeaderFuzzTest {

    private val streams = HevcDecoderTest().let {
        listOf(
            "wavefront" to it.wavefront420, "4:2:2" to it.chroma422, "4:4:4" to it.chroma444, "loop filters" to it.loopFilters420,
            "range extension" to it.rangeExtensionReconstruction, "range extension parsing" to it.rangeExtensionParsing,
        )
    }

    private val shard = FuzzShard.fromEnvironment()

    private fun type(unit: ByteArray) = (unit[0].toInt() shr 1) and 0x3F

    private fun decode(units: List<ByteArray>): Int {
        val d = HevcDecoder()
        for (u in units) d.decodeNal(u, 0, u.size, null)
        return d.finish().size
    }

    /** Decodes [units]: a picture, no picture or ImageDecodeException, and nothing else. True when it decoded. */
    private fun survives(label: String, units: List<ByteArray>): Boolean = try {
        decode(units)
        true
    } catch (_: ImageDecodeException) {
        false
    } catch (t: Throwable) {
        fail("$label${FuzzSettings.replay} leaked ${t::class.simpleName}: ${t.message}")
    }

    /** One syntax element of a header: its first bit, its length, and its value as the parser read it. */
    private class Element(val name: String, val start: Int, val end: Int, val value: Long)

    /** The elements of the header of unit [index], read after the units before it, as the decoder reads them. */
    private fun elements(units: List<ByteArray>, index: Int): List<Element> {
        val store = ParameterSetStore()
        var previous: SliceHeader? = null
        for ((i, unit) in units.withIndex()) {
            if (i > index) break
            val trace = if (i == index) ArrayList<TraceEntry>() else null
            val r = BitReader(unescapeNal(unit), trace)
            val nal = readNalHeader(r)
            when {
                NalType.isSlice(nal.type) -> previous = readSliceHeader(r, nal, store, previous, unit.size)
                nal.type == NalType.SPS || nal.type == NalType.PPS -> store.add(nal, r)
            }
            if (trace != null) {
                val starts = trace.map { it.bit } + r.pos
                return trace.indices.map { k -> Element(trace[k].name, trace[k].bit, maxOf(starts[k + 1], trace[k].bit + 1), trace[k].value) }
                    .filter { it.start >= 16 }
            }
        }
        return emptyList()
    }

    private fun bitsOf(bytes: ByteArray): BooleanArray = BooleanArray(bytes.size * 8) { (bytes[it ushr 3].toInt() shr (7 - (it and 7))) and 1 == 1 }

    private fun bytesOf(bits: List<Boolean>): ByteArray {
        val out = ByteArray((bits.size + 7) / 8)
        for ((i, b) in bits.withIndex()) if (b) out[i ushr 3] = (out[i ushr 3].toInt() or (0x80 ushr (i and 7))).toByte()
        return out
    }

    /** The code number of the Exp-Golomb code that fills [bits] from [start] to [end] exactly, or null. */
    private fun expGolomb(bits: BooleanArray, start: Int, end: Int): Long? {
        var zeros = 0
        var p = start
        while (p < end && !bits[p]) {
            zeros++
            p++
        }
        if (p >= end || zeros > 31 || p + 1 + zeros != end) return null
        var v = 0L
        for (k in 1..zeros) v = (v shl 1) or (if (bits[p + k]) 1L else 0L)
        return (1L shl zeros) - 1 + v
    }

    private fun ueCode(codeNum: Long): List<Boolean> {
        val x = codeNum + 1
        val length = 64 - x.countLeadingZeroBits()
        return List(length - 1) { false } + List(length) { k -> (x shr (length - 1 - k)) and 1L == 1L }
    }

    /** An RBSP with emulation prevention bytes, as a NAL unit carries it (7.4.2). */
    private fun escape(rbsp: ByteArray): ByteArray {
        val out = ArrayList<Byte>(rbsp.size + 8)
        var zeros = 0
        for (b in rbsp) {
            if (zeros >= 2 && (b.toInt() and 0xFF) <= 3) {
                out += 3
                zeros = 0
            }
            out += b
            zeros = if (b.toInt() == 0) zeros + 1 else 0
        }
        if (out.isNotEmpty() && out.last().toInt() == 0) out += 3
        return out.toByteArray()
    }

    /** The values that replace element [e]: Exp-Golomb codes at the edges of every range, or other bits of its length. */
    private fun replacements(bits: BooleanArray, e: Element): List<Pair<String, List<Boolean>>> {
        val length = e.end - e.start
        val own = (e.start until e.end).map { bits[it] }
        val out = ArrayList<Pair<String, List<Boolean>>>()
        val code = expGolomb(bits, e.start, e.end)
        if (code != null) {
            val v = e.value
            val numbers = linkedSetOf(0L, 1L, 2L, 3L, 7L, 15L, 16L, 31L, 63L, 64L, 255L, 256L, 4095L, 65535L, 65536L, 1L shl 24, 0xFFFFFFFEL)
            numbers += listOf(code - 1, code + 1, code * 2 + 1).filter { it in 0..0xFFFFFFFEL }
            for (n in numbers) if (n != code) out += "$v as code $n" to ueCode(n)
        }
        if (length <= 32) {
            out += "zeros" to List(length) { false }
            out += "ones" to List(length) { true }
            out += "first bit" to own.mapIndexed { k, b -> if (k == 0) !b else b }
            if (length > 1) out += "last bit" to own.mapIndexed { k, b -> if (k == length - 1) !b else b }
        }
        return out.filter { it.second != own }
    }

    /** Every element of every header of [units], each replaced in turn. */
    private fun fieldMutants(name: String, units: List<ByteArray>, visit: (String, List<ByteArray>) -> Unit) {
        for ((index, unit) in units.withIndex()) {
            val t = type(unit)
            if (t != NalType.SPS && t != NalType.PPS && !NalType.isSlice(t)) continue
            val rbsp = unescapeNal(unit)
            val bits = bitsOf(rbsp)
            for (e in elements(units, index)) {
                for ((what, replacement) in replacements(bits, e)) {
                    val changed = bits.copyOfRange(0, e.start).toList() + replacement + bits.copyOfRange(e.end, bits.size).toList()
                    val altered = units.toMutableList().also { it[index] = escape(bytesOf(changed)) }
                    visit("$name: unit $index ${e.name} = $what", altered)
                }
            }
        }
    }

    @Test
    fun everyHeaderFieldChangeFailsCleanly() {
        var cases = 0
        var decoded = 0
        var refused = 0
        val names = HashSet<String>()
        for ((name, units) in streams) {
            withDeadline("HEVC header fields, $name${FuzzSettings.replay}", 300_000) {
                fieldMutants(name, units) { label, altered ->
                    names += label.substringAfter(" ").substringBefore(" =")
                    if (!shard.takesNext()) return@fieldMutants
                    cases++
                    if (survives(label, altered)) decoded++ else refused++
                }
            }
        }
        println("HEVC header fields: $cases cases over ${names.size} elements, $decoded decoded, $refused refused")
        if (cases > 0) assertTrue(decoded > 0 && refused > 0, "the mutants both decode and fail, so they reach the decoder: $decoded and $refused")
        assertTrue(names.size > 100, "the trace reaches the elements of every header: ${names.size}")
    }

    @Test
    fun everyParameterSetCutFailsCleanly() {
        for ((name, units) in streams) {
            withDeadline("HEVC parameter set cuts, $name", 120_000) {
                for ((index, unit) in units.withIndex()) {
                    val t = type(unit)
                    if (t != NalType.VPS && t != NalType.SPS && t != NalType.PPS) continue
                    for (n in 0 until unit.size) survives("$name: unit $index cut to $n bytes", units.toMutableList().also { it[index] = unit.copyOf(n) })
                }
            }
        }
    }

    /** One header element of [units] set to [codeNum], written back as an Exp-Golomb code. */
    private fun withField(units: List<ByteArray>, unitType: (Int) -> Boolean, element: String, codeNum: Long): List<ByteArray> {
        val index = units.indexOfFirst { unitType(type(it)) }
        val e = elements(units, index).firstOrNull { it.name == element } ?: fail("no $element in unit $index")
        val bits = bitsOf(unescapeNal(units[index]))
        val changed = bits.copyOfRange(0, e.start).toList() + ueCode(codeNum) + bits.copyOfRange(e.end, bits.size).toList()
        return units.toMutableList().also { it[index] = escape(bytesOf(changed)) }
    }

    @Test
    fun malformedHeadersAreRefused() {
        val units = HevcDecoderTest().wavefront420
        val sps = { t: Int -> t == NalType.SPS }
        val slice = { t: Int -> NalType.isSlice(t) }
        // Each is a decode error: an id past its range, a picture of no samples, entry points past the slice data.
        for ((what, altered) in listOf(
            "an SPS id of 16" to withField(units, sps, "sps_seq_parameter_set_id", 16),
            "a picture 0 samples wide" to withField(units, sps, "pic_width_in_luma_samples", 0),
            "a picture 2^24 samples wide" to withField(units, sps, "pic_width_in_luma_samples", 1L shl 24),
            "a slice of PPS 63, which the stream lacks" to withField(units, slice, "slice_pic_parameter_set_id", 63),
            "4096 entry points" to withField(units, slice, "num_entry_point_offsets", 4096),
        )) {
            val pictures = try {
                decode(altered)
            } catch (_: ImageDecodeException) {
                continue
            }
            assertTrue(pictures == 0, "$what decoded to $pictures pictures")
        }
    }

    // ---- malformed HEIC files through the facade --------------------------------------

    private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun be16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
    private fun box(type: String, payload: ByteArray) = be32(8 + payload.size) + type.encodeToByteArray() + payload
    private fun ispe(w: Int, h: Int) = box("ispe", ByteArray(4) + be32(w) + be32(h)) to false

    /** The hvcC and the sample of the item that [units] code. */
    private fun item(units: List<ByteArray>, withParameterSets: Boolean = true): Pair<ByteArray, ByteArray> {
        val sets = if (withParameterSets) units.filter { type(it) in 32..34 } else emptyList()
        return AnnexB.hvcC(sets) to AnnexB.sample(units.filter { NalType.isSlice(type(it)) })
    }

    private fun heic(build: HeifBuilder.() -> Unit) = HeifBuilder("heic", listOf("mif1", "heic")).apply(build).build()

    @Test
    fun malformedHeicFilesAreDecodeErrors() {
        ImageKodecHeic.install()
        val (big, bigSample) = item(HevcDecoderTest().wavefront420)
        val (small, smallSample) = item(HevcDecoderTest().loopFilters420)
        val (bare, bareSample) = item(HevcDecoderTest().wavefront420, withParameterSets = false)
        val cases = listOf(
            // A grid whose two tiles differ in size.
            "a grid of unequal tiles" to heic {
                add(1, "grid", byteArrayOf(0, 0, 0, 1) + be16(96) + be16(48), listOf(ispe(96, 48)))
                add(2, "hvc1", bigSample, listOf(box("hvcC", big) to true, ispe(48, 48)), hidden = true)
                add(3, "hvc1", smallSample, listOf(box("hvcC", small) to true, ispe(48, 48)), hidden = true)
                references += Triple("dimg", 1L, longArrayOf(2, 3))
            },
            // An hvcC without parameter sets, and none in the sample.
            "an hvcC without parameter sets" to heic { add(1, "hvc1", bareSample, listOf(box("hvcC", bare) to true, ispe(48, 48))) },
            // Grids nested 40 deep, each with one input, the last a coded item (#166).
            "grids nested 40 deep" to heic {
                for (k in 1..40) {
                    add(k, "grid", byteArrayOf(0, 0, 0, 0) + be16(48) + be16(48), listOf(ispe(48, 48)), hidden = k > 1)
                    references += Triple("dimg", k.toLong(), longArrayOf(k + 1L))
                }
                add(41, "hvc1", bigSample, listOf(box("hvcC", big) to true, ispe(48, 48)), hidden = true)
            },
        )
        for ((what, file) in cases) {
            withDeadline("HEIC corpus: $what", 60_000) {
                assertFailsWith<ImageDecodeException>(what) { ImageKodec.decode(file) }
                // probe either reports the file or refuses it; it never leaks another exception.
                try {
                    ImageKodec.probe(file)
                } catch (_: ImageDecodeException) {
                }
            }
        }
    }
}
