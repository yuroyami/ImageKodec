package io.github.yuroyami.imagekodec.codec.avif

import io.github.yuroyami.imagekodec.ExperimentalHeifCodecApi
import io.github.yuroyami.imagekodec.HeifPlanes

/**
 * The AV1 sequence encoder (#177): the pictures of an image sequence in display order, each coded as one temporal
 * unit, a key frame first and inter frames after it, with no frame held back or reordered.
 *
 * It codes each frame with the still encoder's machinery ([Av1Encoder.codeFrame]), which drives the core's own frame
 * decoder in coding mode, so inter prediction, the motion vector stack and the compound average are the decoder's.
 * A decoder of its own stream ([Av1Decoder.endCodedFrame]) ends each frame: it runs the post filters and refreshes
 * the reference slots as section 7.20 says, so the references the next frame predicts from are the ones any decoder
 * of the stream holds.
 *
 * References: a key frame refreshes every slot. Inter frame k after it (k from 1) refreshes slot 1 for odd k and slot
 * 2 for even k, so LAST_FRAME names the slot of frame k - 1 and LAST2_FRAME the slot of frame k - 2, which slot 0
 * holds while that is the key frame; the other five references name LAST2_FRAME's slot. Order hints count the frames
 * since the key frame in 7 bits, and a frame's CDFs start from LAST_FRAME's (primary_ref_frame 0). From frame 2 the
 * frame allows compound prediction (reference_select) where [Av1EncodeSettings.compound] does.
 *
 * [keyFrameInterval] above 0 starts a new key frame every that many frames; 0 codes one key frame only.
 */
@OptIn(ExperimentalHeifCodecApi::class)
internal class Av1SequenceEncoder(
    first: HeifPlanes,
    private val settings: Av1EncodeSettings,
    private val keyFrameInterval: Int,
    cancel: (() -> Unit)?,
) {
    init {
        require(keyFrameInterval >= 0) { "AV1: a key frame interval of $keyFrameInterval" }
    }

    /** The coder of each frame; tests read its tool counts and filters for the frame last coded. */
    val coder = Av1Encoder(first, settings, cancel, sequence = true)
    val stream: Av1StreamParams get() = coder.stream
    private val seqPayload = stream.sequenceHeader()
    private val seq = Av1SequenceHeader.parse(Av1BitReader(seqPayload, 0, seqPayload.size))

    /** The sequence header OBU, which every key frame's temporal unit starts with and `av1C` carries. */
    val sequenceHeaderObu: ByteArray = Av1BitWriter.obu(Av1Decoder.OBU_SEQUENCE_HEADER, seqPayload)

    /** A decoder of the stream written so far, whose reference slots the next frame predicts from. */
    private val mirror = Av1Decoder()
    private var sinceKey = 0
    private val format = Format(first)

    /** The picture the last frame shows, as any decoder of the stream outputs it: for the oracle tests. */
    var lastPicture: Av1Picture? = null
        private set

    /** Whether the last frame coded was a key frame. */
    var lastWasKey: Boolean = false
        private set

    private val compound = settings.compound ?: (settings.effort >= 2)

    /**
     * The next picture as a temporal unit: the sequence header and a key frame, or an inter frame. [cancel] runs
     * before the frame and once a row of superblocks.
     */
    fun encode(frame: HeifPlanes, cancel: (() -> Unit)? = coder.cancel): ByteArray {
        require(Format(frame) == format) { "AV1: every picture of a sequence has the first one's size and format" }
        coder.cancel = cancel
        cancel?.invoke()
        val key = sinceKey == 0 || (keyFrameInterval > 0 && sinceKey >= keyFrameInterval)
        if (key) sinceKey = 0
        val k = sinceKey
        val inter = if (key) {
            Av1InterParams(Av1FrameHeader.KEY_FRAME, 0)
        } else {
            val last = slotOf(k - 1)
            val last2 = slotOf(maxOf(k - 2, 0))
            val idx = IntArray(7) { if (it == 0) last else last2 }
            Av1InterParams(
                Av1FrameHeader.INTER_FRAME, k and 127,
                primaryRefFrame = 0,
                refreshFrameFlags = 1 shl slotOf(k),
                refFrameIdx = idx,
                refOrderHints = IntArray(7) { mirror.refs.orderHint[idx[it]] },
                referenceSelect = compound && k >= 2,
            )
        }
        val coded = coder.codeFrame(frame, seq, inter, mirror.refs)
        lastPicture = mirror.endCodedFrame(coded.header, coded.writer)
        lastWasKey = key
        sinceKey++
        val obu = Av1BitWriter.obu(Av1Decoder.OBU_FRAME, coded.payload)
        return if (key) sequenceHeaderObu + obu else obu
    }

    /** The slot frame [k] after the key frame refreshes: all of them for the key frame, then 1 and 2 in turn. */
    private fun slotOf(k: Int): Int = if (k == 0) 0 else 1 + ((k - 1) and 1)

    /** What every picture of a sequence shares. */
    private data class Format(val width: Int, val height: Int, val depth: Int, val planes: Int, val subX: Int, val subY: Int, val fullRange: Boolean) {
        constructor(p: HeifPlanes) : this(p.width, p.height, p.bitDepthLuma, p.planes.size, p.subX, p.subY, p.fullRange)
    }
}
