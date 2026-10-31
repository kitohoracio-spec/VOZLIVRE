package com.vozlivre.core

/** G.711 µ-law: PCM16 little-endian ↔ 8 bits por amostra (metade do tamanho). */
object Codec {
    private const val BIAS = 0x84
    private const val CLIP = 32635
    private val decodeTable = IntArray(256) { ulawToLinear(it) }

    fun encode(pcm: ByteArray, len: Int): ByteArray {
        val n = len / 2
        val out = ByteArray(n)
        for (i in 0 until n) {
            val s = (pcm[2 * i].toInt() and 0xFF) or (pcm[2 * i + 1].toInt() shl 8)
            out[i] = linearToUlaw(s)
        }
        return out
    }

    fun decode(data: ByteArray): ByteArray {
        val out = ByteArray(data.size * 2)
        for (i in data.indices) {
            val s = decodeTable[data[i].toInt() and 0xFF]
            out[2 * i] = (s and 0xFF).toByte()
            out[2 * i + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun linearToUlaw(sample: Int): Byte {
        var s = sample
        var sign = 0
        if (s < 0) { s = -s; sign = 0x80 }
        if (s > CLIP) s = CLIP
        s += BIAS
        var exp = 7
        var mask = 0x4000
        while (exp > 0 && (s and mask) == 0) { exp--; mask = mask shr 1 }
        val mant = (s shr (exp + 3)) and 0x0F
        return (sign or (exp shl 4) or mant).inv().toByte()
    }

    private fun ulawToLinear(u: Int): Int {
        val x = u.inv() and 0xFF
        val sign = x and 0x80
        val exp = (x shr 4) and 0x07
        val mant = x and 0x0F
        var s = ((mant shl 3) + BIAS) shl exp
        s -= BIAS
        return if (sign != 0) -s else s
    }
}
