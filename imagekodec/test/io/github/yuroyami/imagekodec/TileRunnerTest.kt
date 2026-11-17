package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.avif.AvifContainer
import io.github.yuroyami.imagekodec.codec.avif.AvifProperty
import io.github.yuroyami.imagekodec.codec.avif.HeifCodecs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The tile runner of [ImageKodec.setTileRunner] (#152): whatever order a runner runs the tiles of a
 * HEIC or AVIF grid in, the decode gives the same pixels, the first tile in tile order that fails
 * gives the error, and the cancellation check stops it. The runners here run on one thread; the
 * JVM's `TileRunnerThreadsTest` runs them on four.
 */
@OptIn(ExperimentalHeifCodecApi::class)
class TileRunnerTest {

    @AfterTest
    fun restore() {
        ImageKodec.setTileRunner(TileRunner.Serial)
        HeifCodecs.reset()
    }

    private val reverse = TileRunner { jobs -> for (job in jobs.asReversed()) job.run() }

    private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun be16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
    private fun box(type: String, payload: ByteArray) = be32(8 + payload.size) + type.encodeToByteArray() + payload

    /** Sixteen tiles of libavif's 24 by 16 card, 4:2:0 at 8 bits, as a 4 by 4 grid cut to 91 by 61. */
    private fun av1Grid(): ByteArray {
        val card = AvifContainer.parse(AvifFixtures.plain)
        val item = card.items.getValue(card.primary!!)
        val config = item.property<AvifProperty.Av1Config>()!!
        val flags = (if (config.highBitdepth) 0x40 else 0) or (if (config.twelveBit) 0x20 else 0) or (if (config.monochrome) 0x10 else 0) or
            (config.subX shl 3) or (config.subY shl 2)
        val av1C = box("av1C", byteArrayOf(0x81.toByte(), (config.profile shl 5).toByte(), flags.toByte(), 0) + config.configObus) to true
        val ispe = box("ispe", ByteArray(4) + be32(24) + be32(16)) to false
        return HeifBuilder().apply {
            add(1, "grid", byteArrayOf(0, 0, 3, 3) + be16(91) + be16(61), listOf(box("ispe", ByteArray(4) + be32(91) + be32(61)) to false))
            for (id in 2..17) add(id, "av01", card.itemData(item), listOf(av1C, ispe), hidden = true)
            references += Triple("dimg", 1L, LongArray(16) { it + 2L })
        }.build()
    }

    /** The files of every grid path: AV1 tiles, every chroma format, an alpha grid, one alpha item. */
    private fun grids(): List<Pair<String, ByteArray>> = listOf(
        "a 4 by 4 AV1 grid" to av1Grid(),
        "4:0:0" to HashTileCodec.grid(format = 0),
        "4:2:0 at 10 bits" to HashTileCodec.grid(format = 1, depth = 10),
        "4:2:2 with an alpha grid" to HashTileCodec.grid(format = 2, alpha = HashTileCodec.AlphaKind.GRID, premultiplied = true),
        "4:4:4 with one alpha item" to HashTileCodec.grid(format = 3, depth = 12, alpha = HashTileCodec.AlphaKind.SINGLE),
        "one row of tall tiles" to HashTileCodec.grid(format = 1, rows = 1, columns = 3, tileHeight = 200, height = 9, alpha = HashTileCodec.AlphaKind.GRID),
    )

    /** What every decode of a grid gives: in full, at 16 bits, reduced while decoding, and scaled. */
    private fun decodes(file: ByteArray): List<IntArray> {
        val info = ImageKodec.probe(file)
        return listOf(
            ImageKodec.decode(file).argb,
            ImageKodec.decode16(file).samples.map { it.toInt() }.toIntArray(),
            ImageKodec.decodeDownscaledTo(file, info.width / 3, info.height / 3).argb,
            ImageKodec.decodeScaled(file, info.width - 1, info.height - 1).argb,
        )
    }

    @Test
    fun aRunnerInReverseOrderGivesThePixelsOfTheDefault() {
        ImageKodec.registerHeifCodec(HashTileCodec)
        for ((label, file) in grids()) {
            ImageKodec.setTileRunner(TileRunner.Serial)
            val want = decodes(file)
            var calls = 0
            ImageKodec.setTileRunner { jobs ->
                calls++
                reverse.run(jobs)
            }
            val got = decodes(file)
            assertTrue(calls > 0, "$label: the runner never ran")
            for (k in want.indices) assertContentEquals(want[k], got[k], "$label, decode $k")
        }
    }

    @Test
    fun whatTheRunnerThrowsComesOutOfTheDecode() {
        ImageKodec.registerHeifCodec(HashTileCodec)
        val boom = IllegalStateException("the second job")
        ImageKodec.setTileRunner { jobs ->
            jobs.forEachIndexed { i, job ->
                if (i == 1) throw boom
                job.run()
            }
        }
        val file = HashTileCodec.grid(format = 1)
        assertSame(boom, runCatching { ImageKodec.decode(file) }.exceptionOrNull())
        assertSame(boom, runCatching { ImageKodec.decodeDownscaledTo(file, 20, 15) }.exceptionOrNull())
    }

    /** [HashTileCodec], but the tiles whose number is in [bad] throw an exception that names it. */
    private class FailingTiles(val bad: Set<Int>) : HeifCodec by HashTileCodec {
        override fun open(configuration: ByteArray?): HeifCodecSession {
            val session = HashTileCodec.open(configuration)
            return object : HeifCodecSession {
                override fun decode(sample: ByteArray, cancellationCheck: (() -> Unit)?): HeifPlanes? {
                    val id = ((sample[0].toInt() and 0xFF) shl 8) or (sample[1].toInt() and 0xFF)
                    if (id in bad) throw ImageDecodeException("tile $id")
                    return session.decode(sample, cancellationCheck)
                }
            }
        }
    }

    @Test
    fun theFirstTileThatFailsByTileOrderGivesTheError() {
        // Tiles 5 and 11 fail; in reverse, 11 fails first, and the decode still names 5.
        ImageKodec.registerHeifCodec(FailingTiles(setOf(5, 11)))
        val file = HashTileCodec.grid(format = 1)
        for (runner in listOf(TileRunner.Serial, reverse)) {
            ImageKodec.setTileRunner(runner)
            for (decode in listOf<() -> Unit>({ ImageKodec.decode(file) }, { ImageKodec.decodeDownscaledTo(file, 20, 15) })) {
                val e = runCatching(decode).exceptionOrNull()
                assertTrue(e is ImageDecodeException && e.message == "tile 5", "$e")
            }
        }
    }

    @Test
    fun aJobThatTheRunnerLeavesUnrunFailsTheDecode() {
        ImageKodec.registerHeifCodec(HashTileCodec)
        ImageKodec.setTileRunner { jobs -> jobs.drop(1).forEach { it.run() } }
        val e = runCatching { ImageKodec.decode(HashTileCodec.grid(format = 1)) }.exceptionOrNull()
        assertTrue(e is IllegalStateException && "before job 0" in e.message.orEmpty(), "$e")
    }

    @Test
    fun theCancellationCheckRunsInEachJobAndStopsTheDecode() {
        ImageKodec.registerHeifCodec(HashTileCodec)
        ImageKodec.setTileRunner(reverse)
        val file = HashTileCodec.grid(format = 1)
        for (decode in listOf<(() -> Unit) -> Unit>(
            { check -> ImageKodec.decode(file, cancellationCheck = check) },
            { check -> ImageKodec.decodeDownscaledTo(file, 20, 15, cancellationCheck = check) },
        )) {
            var checks = 0
            decode { checks++ }
            assertTrue(checks >= 16, "$checks checks for 16 tiles")
            val stop = IllegalStateException("stop")
            var left = 6
            val thrown = runCatching { decode { if (--left == 0) throw stop } }.exceptionOrNull()
            assertSame(stop, thrown)
        }
        // An AV1 grid, whose decoder also checks once a row of superblocks.
        val stop = IllegalStateException("stop")
        var left = 20
        assertSame(stop, runCatching { ImageKodec.decode(av1Grid(), cancellationCheck = { if (--left == 0) throw stop }) }.exceptionOrNull())
    }
}
