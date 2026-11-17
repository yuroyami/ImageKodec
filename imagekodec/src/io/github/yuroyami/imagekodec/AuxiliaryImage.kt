package io.github.yuroyami.imagekodec

/**
 * An auxiliary image of the primary image of a HEIC or AVIF file (#154): an image item that an
 * `auxl` reference ties to the primary image, of the kind its `auxC` property names.
 * [ImageKodec.auxiliaryImages] lists them and [ImageKodec.decodeAuxiliaryImage] decodes one.
 *
 * The kinds this library knows by [urn]:
 * - alpha: `urn:mpeg:hevc:2015:auxid:1` (HEVC) and `urn:mpeg:mpegB:cicp:systems:auxiliary:alpha`
 *   (ISO/IEC 23091-2), which `decode` already joins to the colour image;
 * - depth: `urn:mpeg:hevc:2015:auxid:2` (HEVC) and `urn:mpeg:mpegB:cicp:systems:auxiliary:depth`
 *   (ISO/IEC 23091-2), as the portrait photos of a phone carry a depth or disparity map;
 * - the HDR gain map of an Apple photo: `urn:com:apple:photo:2020:aux:hdrgainmap`, which
 *   [ImageKodec.decodeGainMap] also returns.
 *
 * An image of any other URN, such as the mattes of an Apple portrait photo, is listed with its
 * URN as the file names it.
 *
 * @property urn the kind of auxiliary image, as `auxC` names it.
 * @property width the width of the item's picture, as [ImageKodec.decodeAuxiliaryImage] gives it.
 * @property height the height of the item's picture, as [ImageKodec.decodeAuxiliaryImage] gives it.
 * @property bitDepth the bits of each coded sample of the item's luma or gray plane.
 * @property subtype the bytes of `auxC` after the URN's terminating NUL, unparsed, or an empty
 *   array. A HEVC depth image carries a depth representation information SEI message there,
 *   which gives the meaning of its samples and from which libheif's `heif-info` prints d-min and
 *   d-max. The array is the instance's own copy; treat it as read-only.
 */
public class AuxiliaryImage internal constructor(
    public val urn: String,
    public val width: Int,
    public val height: Int,
    public val bitDepth: Int,
    public val subtype: ByteArray,
) {
    override fun toString(): String = "AuxiliaryImage($urn, ${width}x$height, $bitDepth bits, ${subtype.size} subtype bytes)"
}
