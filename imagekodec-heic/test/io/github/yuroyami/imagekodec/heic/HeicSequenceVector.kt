package io.github.yuroyami.imagekodec.heic

import io.github.yuroyami.imagekodec.AnnexB
import io.github.yuroyami.imagekodec.HeifBuilder

/**
 * A HEIC image sequence that every target decodes: the four pictures of `HevcDecoderTest.interPictures`
 * (an IDR, a P and two B pictures, of picture order counts 0, 3, 2 and 1 in decoding order), one sample
 * each, in a track of handler `vide` with brands `msf1` and `hevc`, as ffmpeg writes an MP4. The `ctts`
 * offsets make each composition time the picture order count plus 2, so the frames show in the order
 * the decoded picture buffer outputs them.
 */
internal object HeicSequenceVector {
    private val units = HevcDecoderTest().interPictures

    private fun type(u: ByteArray) = (u[0].toInt() shr 1) and 0x3F

    val parameterSets: List<ByteArray> = units.filter { type(it) in 32..34 }

    /** The NAL units of each sample: a slice and the suffix SEI after it. */
    val samples: List<ByteArray> = run {
        val out = ArrayList<ArrayList<ByteArray>>()
        for (u in units.filter { type(it) !in 32..34 }) {
            if (NalType.isSlice(type(u))) out += ArrayList<ByteArray>()
            out.last() += u
        }
        out.map { AnnexB.sample(it) }
    }

    /** Composition offsets: the picture order counts 0, 3, 2 and 1, plus 2, less the decoding times 0 to 3. */
    val ctts: List<Pair<Int, Int>> = listOf(1 to 2, 1 to 4, 1 to 2, 1 to 0)

    private fun box(type: String, payload: ByteArray): ByteArray {
        val size = 8 + payload.size
        return byteArrayOf((size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte()) + type.encodeToByteArray() + payload
    }

    fun file(entry: String = "hvc1", handler: String = "vide"): ByteArray = HeifBuilder("msf1", listOf("msf1", "hevc", "iso8")).apply {
        primary = null
        addTrack(1, entry, 48, 32, box("hvcC", AnnexB.hvcC(parameterSets)), samples, handler = handler, ctts = ctts, repeat = false)
    }.build()
}
