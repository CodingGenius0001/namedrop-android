package com.keshav.daptap.namedrop

import java.math.BigInteger

/**
 * X25519 (RFC 7748 section 5), the key exchange iPhones offer in TLS 1.3. Android's own provider lacks the
 * standard Java API for it (XECPublicKeySpec / NamedParameterSpec), which kwik's TLS uses, so DapTap carries
 * its own. BigInteger arithmetic: fine for one ephemeral key exchange per tap.
 */
object X25519 {
    private val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val A24: BigInteger = BigInteger.valueOf(121665)
    private val P_MINUS_2: BigInteger = P.subtract(BigInteger.TWO)

    /** The u-coordinate of the base point (9), little-endian. */
    val BASE: ByteArray = ByteArray(32).also { it[0] = 9 }

    fun scalarMult(scalar: ByteArray, u: ByteArray): ByteArray {
        require(scalar.size == 32 && u.size == 32)
        val k = decodeScalar(scalar)
        val x1 = decodeU(u)
        var x2 = BigInteger.ONE; var z2 = BigInteger.ZERO
        var x3 = x1; var z3 = BigInteger.ONE
        var swap = 0
        for (t in 254 downTo 0) {
            val kt = if (k.testBit(t)) 1 else 0
            swap = swap xor kt
            if (swap == 1) { val tx = x2; x2 = x3; x3 = tx; val tz = z2; z2 = z3; z3 = tz }
            swap = kt
            val a = (x2 + z2).mod(P); val aa = (a * a).mod(P)
            val b = (x2 - z2).mod(P); val bb = (b * b).mod(P)
            val e = (aa - bb).mod(P)
            val c = (x3 + z3).mod(P); val d = (x3 - z3).mod(P)
            val da = (d * a).mod(P); val cb = (c * b).mod(P)
            x3 = (da + cb).mod(P).let { (it * it).mod(P) }
            z3 = (x1 * (da - cb).mod(P).let { (it * it).mod(P) }).mod(P)
            x2 = (aa * bb).mod(P)
            z2 = (e * (aa + A24 * e).mod(P)).mod(P)
        }
        if (swap == 1) { x2 = x3; z2 = z3 }
        return encodeU((x2 * z2.modPow(P_MINUS_2, P)).mod(P))
    }

    fun publicKey(privateKey: ByteArray): ByteArray = scalarMult(privateKey, BASE)

    fun decodeScalar(k: ByteArray): BigInteger {
        val c = k.copyOf()
        c[0] = (c[0].toInt() and 248).toByte()
        c[31] = ((c[31].toInt() and 127) or 64).toByte()
        return littleEndian(c)
    }

    fun decodeU(u: ByteArray): BigInteger {
        val c = u.copyOf()
        c[31] = (c[31].toInt() and 127).toByte()
        return littleEndian(c).mod(P)
    }

    fun encodeU(v: BigInteger): ByteArray {
        val be = v.toByteArray()
        val out = ByteArray(32)
        for (i in 0 until minOf(32, be.size)) out[i] = be[be.size - 1 - i]
        return out
    }

    fun littleEndian(b: ByteArray): BigInteger = BigInteger(1, b.reversedArray())
}
