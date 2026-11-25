package com.clipshrink.app

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Builds a 3D LUT that converts HDR10 (PQ, BT.2020) pixels, read as if they were SDR,
 * into BT.709 SDR with a soft highlight roll-off instead of hard clipping.
 *
 * Input to the LUT: R'G'B' code values produced by a BT.709 YCbCr->RGB conversion of PQ data.
 * Steps per grid point: undo the 709 matrix and redo it as BT.2020, PQ -> nits, exposure,
 * BT.2020 -> BT.709 primaries, max-RGB filmic (Hable) tone map, gamma encode.
 *
 * Default exposure maps roughly: 150 nits -> code 130, 350 -> 172, 800 -> 212, 2000 -> 244,
 * full white at ~3200 nits, so sunny game scenes keep texture instead of turning white.
 */
object HdrLut {
    const val SIZE = 33
    private const val EXPOSURE = 0.005      // nits -> filmic curve input
    private const val WHITE = 16.0          // curve input that reaches full white

    private fun hable(x: Double): Double {
        val a = 0.15; val b = 0.50; val c = 0.10; val d = 0.20; val e = 0.02; val f = 0.30
        return ((x * (a * x + c * b) + d * e) / (x * (a * x + b) + d * f)) - e / f
    }

    private val WHITE_SCALE = hable(WHITE)

    private fun pqToNits(e: Double): Double {
        val m1 = 0.1593017578125
        val m2 = 78.84375
        val c1 = 0.8359375
        val c2 = 18.8515625
        val c3 = 18.6875
        val p = max(e, 0.0).pow(1.0 / m2)
        val num = max(p - c1, 0.0)
        val den = c2 - c3 * p
        return 10000.0 * (num / den).pow(1.0 / m1)
    }

    /** level -3..+3 changes exposure by 40% per step. */
    fun build(level: Int): Array<Array<IntArray>> {
        val exposure = EXPOSURE * 1.4.pow(level.toDouble())
        return Array(SIZE) { ri ->
            Array(SIZE) { gi ->
                IntArray(SIZE) { bi ->
                    val r = ri / (SIZE - 1.0)
                    val g = gi / (SIZE - 1.0)
                    val b = bi / (SIZE - 1.0)
                    // Back to YCbCr using BT.709 coefficients, forward with BT.2020.
                    val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
                    val cb = (b - y) / 1.8556
                    val cr = (r - y) / 1.5748
                    val r2 = (y + 1.4746 * cr).coerceIn(0.0, 1.0)
                    val b2 = (y + 1.8814 * cb).coerceIn(0.0, 1.0)
                    val g2 = ((y - 0.2627 * r2 - 0.0593 * b2) / 0.6780).coerceIn(0.0, 1.0)
                    // Linear nits scaled into the curve's input range.
                    val lr = pqToNits(r2) * exposure
                    val lg = pqToNits(g2) * exposure
                    val lb = pqToNits(b2) * exposure
                    // BT.2020 -> BT.709 primaries.
                    var o0 = max(1.6605 * lr - 0.5876 * lg - 0.0728 * lb, 0.0)
                    var o1 = max(-0.1246 * lr + 1.1329 * lg - 0.0083 * lb, 0.0)
                    var o2 = max(-0.0182 * lr - 0.1006 * lg + 1.1187 * lb, 0.0)
                    // Filmic curve on the max channel: keeps hue, deep shadows and soft highlights.
                    val m = max(o0, max(o1, o2))
                    if (m > 1e-6) {
                        val t = max(hable(m), 0.0) / WHITE_SCALE
                        val k = t / m
                        o0 *= k; o1 *= k; o2 *= k
                    }
                    fun enc(v: Double) = (min(max(v, 0.0), 1.0).pow(1.0 / 2.2) * 255.0 + 0.5).toInt().coerceIn(0, 255)
                    (0xFF shl 24) or (enc(o0) shl 16) or (enc(o1) shl 8) or enc(o2)
                }
            }
        }
    }
}
