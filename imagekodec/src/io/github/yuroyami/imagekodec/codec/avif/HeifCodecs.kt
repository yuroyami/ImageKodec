package io.github.yuroyami.imagekodec.codec.avif

import io.github.yuroyami.imagekodec.ExperimentalHeifCodecApi
import io.github.yuroyami.imagekodec.HeifCodec
import io.github.yuroyami.imagekodec.HeifCodecInfo
import io.github.yuroyami.imagekodec.HeifCodecSession
import io.github.yuroyami.imagekodec.HeifEncoder
import io.github.yuroyami.imagekodec.HeifPlanes
import io.github.yuroyami.imagekodec.ImageDecodeException
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * The codecs of the HEIF item types this build decodes, by item type. AV1 is built in; a module
 * adds others through [io.github.yuroyami.imagekodec.ImageKodec.registerHeifCodec]. Registration
 * swaps an immutable map atomically, so it is safe from several threads, and readers never lock.
 */
@OptIn(ExperimentalHeifCodecApi::class, ExperimentalAtomicApi::class)
internal object HeifCodecs {

    private class Table(val byItem: Map<Int, HeifCodec>, val bySampleEntry: Map<Int, HeifCodec>, val configurations: Set<Int>)

    private fun table(codecs: Collection<HeifCodec>) = Table(
        codecs.associateBy { fourcc(it.itemType) },
        codecs.flatMap { c -> c.sampleEntryTypes.map { fourcc(it) to c } }.toMap(),
        codecs.map { fourcc(it.configurationType) }.toSet(),
    )

    private val builtIn: List<HeifCodec> = listOf(Av1Codec, JpegItemCodec)
    private val current = AtomicReference(table(builtIn))

    fun register(codec: HeifCodec) {
        for (t in listOf(codec.itemType, codec.configurationType) + codec.sampleEntryTypes) {
            require(t.length == 4 && t.all { it.code in 0x20..0x7E }) { "'$t' is not a four-character code" }
        }
        while (true) {
            val old = current.load()
            val codecs = old.byItem.values.filter { it.itemType != codec.itemType } + codec
            if (current.compareAndSet(old, table(codecs))) return
        }
    }

    /** Back to the built-in codecs and encoders alone, for tests. */
    fun reset() {
        current.store(table(builtIn))
        encoders.store(builtInEncoders)
    }

    /** The encoders by item type (#174): AV1's is built in (#176), as AV1 is royalty-free. */
    private val builtInEncoders: Map<String, HeifEncoder> = mapOf(Av1HeifEncoder.itemType to Av1HeifEncoder)
    private val encoders = AtomicReference(builtInEncoders)

    fun registerEncoder(encoder: HeifEncoder) {
        for (t in listOf(encoder.itemType, encoder.configurationType)) {
            require(t.length == 4 && t.all { it.code in 0x20..0x7E }) { "'$t' is not a four-character code" }
        }
        while (true) {
            val old = encoders.load()
            if (encoders.compareAndSet(old, old + (encoder.itemType to encoder))) return
        }
    }

    fun encoder(itemType: String): HeifEncoder? = encoders.load()[itemType]

    fun forItem(type: Int): HeifCodec? = current.load().byItem[type]

    fun forSampleEntry(type: Int): HeifCodec? = current.load().bySampleEntry[type]

    /** The property types that configure a registered codec: a reader knows them. */
    fun isConfiguration(type: Int): Boolean = type in current.load().configurations
}

/** The AV1 decoder of the core as the codec of `av01` items and tracks. */
@OptIn(ExperimentalHeifCodecApi::class)
internal object Av1Codec : HeifCodec {
    override val itemType: String = "av01"
    override val sampleEntryTypes: Set<String> = setOf("av01")
    override val configurationType: String = "av1C"

    /** The configuration OBUs after the four fixed bytes of `av1C` (AV1-ISOBMFF section 2.3). */
    private fun obus(configuration: ByteArray?): ByteArray? =
        configuration?.let { if (it.size > 4) it.copyOfRange(4, it.size) else ByteArray(0) }

    override fun inspect(configuration: ByteArray?, data: ByteArray?): HeifCodecInfo {
        if (data == null) {
            val c = configuration ?: throw ImageDecodeException("AVIF: an AV1 item without 'av1C' or data")
            if (c.size < 4) throw ImageDecodeException("AVIF: 'av1C' of ${c.size} bytes")
            val b2 = c[2].toInt() and 0xFF
            val depth = if ((b2 and 0x20) != 0) 12 else if ((b2 and 0x40) != 0) 10 else 8
            val chroma = if ((b2 and 0x10) != 0) 0 else if ((b2 and 0x08) == 0) 3 else if ((b2 and 0x04) == 0) 2 else 1
            return HeifCodecInfo(0, 0, depth, depth, chroma)
        }
        val h = Av1Decoder.inspect(obus(configuration), data)
        val seq = h.seq
        return HeifCodecInfo(
            h.width, h.height, seq.bitDepth, seq.bitDepth,
            chromaFormat = if (seq.monochrome) 0 else if (seq.subsamplingX == 0) 3 else if (seq.subsamplingY == 0) 2 else 1,
            colorPrimaries = seq.colorPrimaries, transferCharacteristics = seq.transferCharacteristics,
            matrixCoefficients = seq.matrixCoefficients, fullRange = seq.colorRange,
            unsupportedReason = h.unsupported,
        )
    }

    override fun open(configuration: ByteArray?): HeifCodecSession = open(configuration, 0, 0xFFFF, still = false)

    /**
     * A session at AV1 operating point [operatingPoint] (`a1op`), showing spatial layer [layer]
     * (`lsel`). A [still] session decodes one item; a sequence session decodes temporal units in order.
     */
    fun open(configuration: ByteArray?, operatingPoint: Int, layer: Int, still: Boolean): HeifCodecSession {
        val config = obus(configuration)
        val decoder = Av1Decoder(operatingPoint)
        if (!still) config?.takeIf { it.isNotEmpty() }?.let { decoder.configure(it) }
        return object : HeifCodecSession {
            override fun decode(sample: ByteArray, cancellationCheck: (() -> Unit)?): HeifPlanes? {
                decoder.cancel = cancellationCheck
                val pic = if (still) decoder.decodeStill(config, sample, layer) else decoder.decodeTemporalUnit(sample) ?: return null
                return HeifPlanes(pic.width, pic.height, pic.bitDepth, pic.bitDepth, pic.subX, pic.subY, pic.planes, pic.strides, pic.seq.colorRange)
            }
        }
    }
}

/**
 * The core's JPEG decoder as the codec of `jpeg` items (ISO/IEC 23008-12 Annex H), as a camera
 * writes its smallest thumbnail (#167). Its planes are the decoded RGB, as G, B and R planes for
 * the identity matrix of ITU-T H.273, so the conversion to RGB gives them back unchanged.
 */
@OptIn(ExperimentalHeifCodecApi::class)
internal object JpegItemCodec : HeifCodec {
    override val itemType: String = "jpeg"
    override val sampleEntryTypes: Set<String> = emptySet()
    override val configurationType: String = "jpgC"

    /** The stream of an item: the prefix of 'jpgC' first when the item has one, without a second SOI. */
    private fun stream(configuration: ByteArray?, data: ByteArray): ByteArray {
        if (configuration == null || configuration.isEmpty()) return data
        val soi = data.size >= 2 && (data[0].toInt() and 0xFF) == 0xFF && (data[1].toInt() and 0xFF) == 0xD8
        return configuration + if (soi) data.copyOfRange(2, data.size) else data
    }

    override fun inspect(configuration: ByteArray?, data: ByteArray?): HeifCodecInfo {
        if (data == null) return HeifCodecInfo(0, 0, 8, 8, chromaFormat = 3)
        val info = io.github.yuroyami.imagekodec.codec.ImageProbe.probe(stream(configuration, data))
        return HeifCodecInfo(
            info.width, info.height, 8, 8, chromaFormat = 3,
            colorPrimaries = 2, transferCharacteristics = 13, matrixCoefficients = 0, fullRange = true,
            unsupportedReason = if (info.isDecodable) null else info.unsupportedReason,
        )
    }

    override fun open(configuration: ByteArray?): HeifCodecSession = object : HeifCodecSession {
        override fun decode(sample: ByteArray, cancellationCheck: (() -> Unit)?): HeifPlanes {
            val bitmap = io.github.yuroyami.imagekodec.codec.JpegDecoder.decode(stream(configuration, sample), cancel = cancellationCheck)
            val n = bitmap.width * bitmap.height
            val g = ShortArray(n)
            val b = ShortArray(n)
            val r = ShortArray(n)
            for (i in 0 until n) {
                val p = bitmap.argb[i]
                r[i] = ((p ushr 16) and 0xFF).toShort()
                g[i] = ((p ushr 8) and 0xFF).toShort()
                b[i] = (p and 0xFF).toShort()
            }
            return HeifPlanes(bitmap.width, bitmap.height, 8, 8, 0, 0, arrayOf(g, b, r), IntArray(3) { bitmap.width }, true)
        }
    }
}
