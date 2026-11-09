/*
 * JPEG XR adaptive variable length codes: a Kotlin port of jxrlib's AdaptDiscriminant and its
 * decode tables (image/sys/adapthuff.c), at 4creators/jxrlib commit 1ef17dd. The tables are
 * copied from that file by tools/jxr_tables.py, which prints them.
 *
 * Copyright (c) Microsoft Corp. All rights reserved.
 *
 * Use of this source code is governed by the BSD 2-Clause license that is reproduced in this
 * repository's NOTICE.
 */
package io.github.yuroyami.imagekodec.codec.jxr

/**
 * One adaptive code table of ITU-T T.832 clause 9.13 (the AdaptiveVLC process): the code tables of
 * an alphabet of [symbols], of which a discriminant chooses one, moved by each decoded symbol and
 * checked when the context adapts.
 */
internal class JxrVlc(val symbols: Int) {
    var initialized = false
    var tableIndex = 0
    var discriminant = 0
    var discriminant1 = 0
    private var upperBound = 0
    private var lowerBound = 0

    lateinit var table: IntArray
        private set
    private var delta: IntArray? = null
    private var deltaAt = 0
    private var delta1: IntArray? = null
    private var delta1At = 0

    /** Moves the discriminant by decoded [symbol]. */
    fun count(symbol: Int) {
        val d = delta ?: return
        discriminant += d[deltaAt + symbol]
    }

    /** Moves both discriminants by decoded [symbol], for the alphabets of 6 and 12 that keep two. */
    fun count2(symbol: Int) {
        count(symbol)
        val d1 = delta1 ?: return
        discriminant1 += d1[delta1At + symbol]
    }

    /** AdaptDiscriminant: picks the next table when a discriminant has passed its bound. */
    fun adapt() {
        val maxTables = MAX_TABLES[symbols]
        val second = SECOND_DISC[symbols]
        if (!initialized) {
            initialized = true
            discriminant = 0
            discriminant1 = 0
            tableIndex = second
        }
        val dL = discriminant
        val dH = if (second != 0) discriminant1 else discriminant
        var change = false
        if (dL < lowerBound) {
            tableIndex--
            change = true
        } else if (dH > upperBound) {
            tableIndex++
            change = true
        }
        if (change) {
            discriminant = 0
            discriminant1 = 0
        }
        discriminant = discriminant.coerceIn(-THRESHOLD * MEMORY, THRESHOLD * MEMORY)
        discriminant1 = discriminant1.coerceIn(-THRESHOLD * MEMORY, THRESHOLD * MEMORY)
        val t = tableIndex
        lowerBound = if (t == 0) Int.MIN_VALUE else -THRESHOLD
        upperBound = if (t == maxTables - 1) 1 shl 30 else THRESHOLD
        delta = null
        delta1 = null
        when (symbols) {
            4 -> table = LOOKUP4[0]
            5 -> {
                table = LOOKUP5[t]
                delta = DELTA5
                deltaAt = 0
            }
            6 -> {
                delta1 = DELTA6
                delta1At = symbols * (t - if (t + 1 == maxTables) 1 else 0)
                delta = DELTA6
                deltaAt = (t - 1 + if (t == 0) 1 else 0) * symbols
                table = LOOKUP6[t]
            }
            7 -> {
                table = LOOKUP7[t]
                delta = DELTA7
                deltaAt = 0
            }
            8 -> table = LOOKUP8[0]
            9 -> {
                table = LOOKUP9[t]
                delta = DELTA9
                deltaAt = 0
            }
            else -> {
                delta1 = DELTA12
                delta1At = symbols * (t - if (t + 1 == maxTables) 1 else 0)
                delta = DELTA12
                deltaAt = (t - 1 + if (t == 0) 1 else 0) * symbols
                table = LOOKUP12[t]
            }
        }
    }

    private companion object {
        const val THRESHOLD = 8
        const val MEMORY = 8

        /** How many tables each alphabet size has (gMaxTables), and which start at table 1 (gSecondDisc). */
        val MAX_TABLES = intArrayOf(0, 0, 0, 0, 1, 2, 4, 2, 2, 2, 0, 0, 5)
        val SECOND_DISC = intArrayOf(0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1)

        val LOOKUP4: Array<IntArray> = arrayOf(
            intArrayOf(
                19, 19, 19, 19, 27, 27, 27, 27, 10, 10, 10, 10, 10, 10, 10, 10,
                1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
                0, 0, 0, 0, 0, 0, 0, 0,
            ),
        )

        val LOOKUP5: Array<IntArray> = arrayOf(
            intArrayOf(
                28, 28, 36, 36, 19, 19, 19, 19, 10, 10, 10, 10, 10, 10, 10, 10,
                1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            ),
            intArrayOf(
                11, 11, 11, 11, 19, 19, 19, 19, 27, 27, 27, 27, 35, 35, 35, 35,
                1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            ),
        )

        val LOOKUP6: Array<IntArray> = arrayOf(
            intArrayOf(
                13, 29, 44, 44, 19, 19, 19, 19, 34, 34, 34, 34, 34, 34, 34, 34,
                1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            ),
            intArrayOf(
                12, 12, 28, 28, 43, 43, 43, 43, 2, 2, 2, 2, 2, 2, 2, 2,
                18, 18, 18, 18, 18, 18, 18, 18, 34, 34, 34, 34, 34, 34, 34, 34,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            ),
            intArrayOf(
                4, 4, 12, 12, 43, 43, 43, 43, 18, 18, 18, 18, 18, 18, 18, 18,
                26, 26, 26, 26, 26, 26, 26, 26, 34, 34, 34, 34, 34, 34, 34, 34,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            ),
            intArrayOf(
                5, 13, 36, 36, 43, 43, 43, 43, 18, 18, 18, 18, 18, 18, 18, 18,
                25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25, 25,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            ),
        )

        val LOOKUP7: Array<IntArray> = arrayOf(
            intArrayOf(
                45, 53, 36, 36, 27, 27, 27, 27, 2, 2, 2, 2, 2, 2, 2, 2,
                10, 10, 10, 10, 10, 10, 10, 10, 18, 18, 18, 18, 18, 18, 18, 18,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            ),
            intArrayOf(
                -32736, 37, 28, 28, 19, 19, 19, 19, 10, 10, 10, 10, 10, 10, 10, 10,
                1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
                5, 6, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            ),
        )

        val LOOKUP8: Array<IntArray> = arrayOf(
            intArrayOf(
                53, 21, 28, 28, 11, 11, 11, 11, 43, 43, 43, 43, 59, 59, 59, 59,
                2, 2, 2, 2, 2, 2, 2, 2, 34, 34, 34, 34, 34, 34, 34, 34,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            ),
            intArrayOf(
                52, 52, 20, 20, 3, 3, 3, 3, 11, 11, 11, 11, 27, 27, 27, 27,
                35, 35, 35, 35, 43, 43, 43, 43, 58, 58, 58, 58, 58, 58, 58, 58,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            ),
        )

        val LOOKUP9: Array<IntArray> = arrayOf(
            intArrayOf(
                13, 29, 37, 61, 20, 20, 68, 68, 3, 3, 3, 3, 51, 51, 51, 51,
                41, 41, 41, 41, 41, 41, 41, 41, 41, 41, 41, 41, 41, 41, 41, 41,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0,
            ),
            intArrayOf(
                -32736, 53, 28, 28, 11, 11, 11, 11, 19, 19, 19, 19, 43, 43, 43, 43,
                1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
                -32734, 4, 7, 8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0,
            ),
        )

        val LOOKUP12: Array<IntArray> = arrayOf(
            intArrayOf(
                -32736, 5, 76, 76, 37, 53, 69, 85, 43, 43, 43, 43, 91, 91, 91, 91,
                57, 57, 57, 57, 57, 57, 57, 57, 57, 57, 57, 57, 57, 57, 57, 57,
                -32734, 1, 2, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0,
            ),
            intArrayOf(
                -32736, 85, 13, 53, 4, 4, 36, 36, 43, 43, 43, 43, 67, 67, 67, 67,
                75, 75, 75, 75, 91, 91, 91, 91, 58, 58, 58, 58, 58, 58, 58, 58,
                2, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0,
            ),
            intArrayOf(
                -32736, 37, 92, 92, 11, 11, 11, 11, 43, 43, 43, 43, 59, 59, 59, 59,
                67, 67, 67, 67, 75, 75, 75, 75, 2, 2, 2, 2, 2, 2, 2, 2,
                -32734, -32732, 2, 3, 6, 10, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0,
            ),
            intArrayOf(
                -32736, 29, 37, 69, 3, 3, 3, 3, 43, 43, 43, 43, 59, 59, 59, 59,
                75, 75, 75, 75, 91, 91, 91, 91, 10, 10, 10, 10, 10, 10, 10, 10,
                -32734, 10, 2, 6, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0,
            ),
            intArrayOf(
                -32736, 93, 28, 28, 60, 60, 76, 76, 3, 3, 3, 3, 43, 43, 43, 43,
                9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9,
                -32734, -32732, -32730, 2, 4, 8, 6, 10, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0,
            ),
        )

        val DELTA5: IntArray = intArrayOf(0, -1, 0, 1, 1)

        val DELTA6: IntArray = intArrayOf(-1, 1, 1, 1, 0, 1, -2, 0, 0, 2, 0, 0, -1, -1, 0, 1, -2, 0)

        val DELTA7: IntArray = intArrayOf(1, 0, -1, -1, -1, -1, -1)

        val DELTA9: IntArray = intArrayOf(2, 2, 1, 1, -1, -2, -2, -2, -3)

        val DELTA12: IntArray = intArrayOf(1, 1, 1, 1, 1, 0, 0, -1, 2, 1, 0, 0, 2, 2, -1, -1, -1, 0, -2, -1, 0, 0, -2, -1, -1, 1, 0, 2, 0, 0, 0, 0, -2, 0, 1, 1, 0, 1, 0, 1, -2, 0, -1, -1, -2, -1, -2, -2)
    }
}
