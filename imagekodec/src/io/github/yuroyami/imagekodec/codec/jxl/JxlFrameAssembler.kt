package io.github.yuroyami.imagekodec.codec.jxl

/**
 * Collects the sections of one frame (18181-1 C.3) as pieces: bits already written, the place
 * of the frame's shared Modular tree, and Modular streams. [finish] learns one tree from every
 * stream of the frame, codes them all with one entropy code, and gives the sections' bytes, or
 * one section when the frame has a single group and pass.
 */
internal class JxlFrameAssembler(private val dim: JxlFrameDim, private val effort: Int) {
    /** The sections in the decoder's order: global, each LF group, HF global, each group. */
    val sections: List<MutableList<Any>> = List(2 + dim.numDcGroups + dim.numGroups) { ArrayList() }

    val global: MutableList<Any> get() = sections[0]
    fun lfGroup(g: Int): MutableList<Any> = sections[1 + g]
    val hfGlobal: MutableList<Any> get() = sections[1 + dim.numDcGroups]
    fun group(g: Int): MutableList<Any> = sections[2 + dim.numDcGroups + g]

    /** Marks where the frame says whether it has a shared tree, and writes it. */
    object TreeMarker

    // The numbers of the Modular streams of a frame, as JxlModularFrame gives them.
    fun lfCoefficientsStream(g: Int): Int = 1 + g
    fun hfMetadataStream(g: Int): Int = 1 + 2 * dim.numDcGroups + g
    fun passGroupStream(g: Int): Int = 1 + 3 * dim.numDcGroups + 17 + g

    fun finish(cancel: (() -> Unit)?): List<ByteArray> {
        val streams = ArrayList<JxlModStream>()
        for (s in sections) for (p in s) if (p is JxlModStream && JxlModularEncoder.hasSamples(p)) streams.add(p)
        var tree: JxlEncTree? = null
        var code: JxlEntropyCode? = null
        val tokens = HashMap<JxlModStream, JxlTokens>()
        if (streams.isNotEmpty()) {
            val t = JxlModularEncoder.learnTree(streams, effort)
            cancel?.invoke()
            for (st in streams) {
                val tk = JxlTokens()
                JxlModularEncoder.tokenize(st, t, tk)
                tokens[st] = tk
                cancel?.invoke()
            }
            code = JxlEntropyEncoder.build(streams.map { tokens[it]!! }, t.leaves, effort)
            tree = t
        }
        val single = dim.numGroups == 1
        val out = ArrayList<ByteArray>()
        var bw = JxlBitWriter()
        for ((i, s) in sections.withIndex()) {
            for (p in s) {
                when (p) {
                    is JxlBitWriter -> bw.appendBits(p)
                    TreeMarker -> {
                        bw.bool(tree != null)
                        if (tree != null) {
                            tree.write(bw, effort)
                            code!!.writeHeader(bw)
                        }
                    }
                    is JxlModStream -> {
                        val tk = tokens[p]
                        if (tk == null) {
                            // A stream with no samples is its header alone.
                            if (p.channels.isNotEmpty()) JxlModularEncoder.writeHeader(bw, p, tree != null)
                        } else {
                            JxlModularEncoder.writeShared(bw, p, code!!, tk)
                        }
                    }
                    else -> error("unknown section piece")
                }
            }
            if (!single || i == sections.size - 1) {
                bw.zeroPadToByte()
                out.add(bw.toByteArray())
                bw = JxlBitWriter()
            }
        }
        return out
    }
}
