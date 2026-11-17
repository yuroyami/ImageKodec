package io.github.yuroyami.imagekodec

import io.github.yuroyami.imagekodec.codec.avif.HeifCodecs
import java.lang.management.ManagementFactory
import java.nio.file.Files
import jdk.jfr.Recording
import jdk.jfr.consumer.RecordingFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * A HEIC or AVIF grid reduced while it decodes must never hold the picture at full size (#151).
 * The JVM counts the bytes each thread allocates, as [ReducedMemoryTest] reads them, and Flight
 * Recorder reports each allocation that does not fit a thread's local buffer, which every array
 * of a megabyte or more is, so the largest single allocation is measured, not inferred.
 */
class HeifGridMemoryTest {

    @AfterTest
    fun forgetTheCodec() = HeifCodecs.reset()

    private fun allocated(block: () -> Unit): Long {
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val id = Thread.currentThread().id
        val before = bean.getThreadAllocatedBytes(id)
        block()
        return bean.getThreadAllocatedBytes(id) - before
    }

    /** The size of the largest single allocation [block] makes on this thread. */
    private fun largestAllocation(block: () -> Unit): Long {
        val file = Files.createTempFile("heif-grid", ".jfr")
        try {
            Recording().use { r ->
                r.enable("jdk.ObjectAllocationOutsideTLAB")
                r.enable("jdk.ObjectAllocationInNewTLAB")
                r.start()
                block()
                r.stop()
                r.dump(file)
            }
            val thread = Thread.currentThread().id
            return RecordingFile.readAllEvents(file)
                .filter { it.thread?.javaThreadId == thread }
                .maxOf { it.getLong("allocationSize") }
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun aGridReducedWhileItDecodesAllocatesUnderAQuarterOfItsCanvas() {
        ImageKodec.registerHeifCodec(HashTileCodec)
        // 2,043 by 2,045 pixels in a 16 by 16 grid of 128 by 128 tiles, 4:2:0 at 10 bits.
        val file = HashTileCodec.grid(format = 1, depth = 10, rows = 16, columns = 16, tileWidth = 128, tileHeight = 128)
        val info = ImageKodec.probe(file)
        val canvas = info.width.toLong() * info.height * 4
        val w = info.width / 4
        val h = info.height / 4
        repeat(2) { ImageKodec.decodeDownscaledTo(file, w, h); ImageKodec.decode(file).downscaledTo(w, h) } // warm up
        assertContentEquals(ImageKodec.decode(file).downscaledTo(w, h).argb, ImageKodec.decodeDownscaledTo(file, w, h).argb)
        val fullLargest = largestAllocation { ImageKodec.decode(file).downscaledTo(w, h) }
        val reducedLargest = largestAllocation { ImageKodec.decodeDownscaledTo(file, w, h) }
        val fullTotal = allocated { ImageKodec.decode(file).downscaledTo(w, h) }
        val reducedTotal = allocated { ImageKodec.decodeDownscaledTo(file, w, h) }
        val report = "canvas $canvas bytes as ARGB; decode then downscaledTo: largest $fullLargest, total $fullTotal; " +
            "decodeDownscaledTo: largest $reducedLargest, total $reducedTotal"
        println(report)
        assertTrue(fullLargest >= canvas, report)
        assertTrue(reducedLargest < canvas / 4, report)
    }
}
