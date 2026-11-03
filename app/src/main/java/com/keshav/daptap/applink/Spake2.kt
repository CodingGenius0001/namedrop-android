package com.keshav.daptap.applink

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * SPAKE2 over edwards25519, exactly as BoringSSL implements it (crypto/curve25519/spake25519.cc), which is
 * what Android's Wireless debugging uses to check the pairing code. Plain BigInteger arithmetic: it runs once
 * per pairing.
 */
class Spake2(private val alice: Boolean, private val myName: ByteArray, private val theirName: ByteArray, random: SecureRandom = SecureRandom()) {
    private val privateKey: BigInteger
    private var passwordScalar = BigInteger.ZERO
    private var passwordHash = ByteArray(64)
    private var myMsg = ByteArray(32)

    init {
        val r = ByteArray(64).also { random.nextBytes(it) }
        privateKey = le(r).mod(Ed.L).shiftLeft(3) // a multiple of 8 clears the cofactor
    }

    fun generateMessage(password: ByteArray): ByteArray {
        passwordHash = MessageDigest.getInstance("SHA-512").digest(password)
        var w = le(passwordHash).mod(Ed.L)
        // BoringSSL's fix for its missing left_shift_3: add l, 2l, 4l to make the scalar a multiple of 8.
        var order = Ed.L
        for (bit in 0..2) {
            if (w.testBit(bit)) w += order
            order = order.shiftLeft(1)
        }
        passwordScalar = w
        val mask = Ed.mul(if (alice) Ed.M else Ed.N, w)
        myMsg = Ed.encode(Ed.add(Ed.mul(Ed.B, privateKey), mask))
        return myMsg.copyOf()
    }

    /** The 64-byte shared key, or null if [theirMsg] isn't a curve point. */
    fun processMessage(theirMsg: ByteArray): ByteArray? {
        if (theirMsg.size != 32) return null
        val qStar = Ed.decode(theirMsg) ?: return null
        val peersMask = Ed.mul(if (alice) Ed.N else Ed.M, passwordScalar)
        val q = Ed.add(qStar, Ed.neg(peersMask))
        val dh = Ed.encode(Ed.mul(q, privateKey))
        val sha = MessageDigest.getInstance("SHA-512")
        fun put(b: ByteArray) {
            var l = b.size.toLong()
            sha.update(ByteArray(8) { (l and 0xFF).toByte().also { l = l ushr 8 } })
            sha.update(b)
        }
        if (alice) { put(myName); put(theirName); put(myMsg); put(theirMsg) }
        else { put(theirName); put(myName); put(theirMsg); put(myMsg) }
        put(dh)
        put(passwordHash)
        return sha.digest()
    }

    private fun le(b: ByteArray) = BigInteger(1, b.reversedArray())

    /** edwards25519 in extended coordinates (X:Y:Z:T), a = -1. */
    internal object Ed {
        val P: BigInteger = BigInteger.ONE.shiftLeft(255) - BigInteger.valueOf(19)
        val L: BigInteger = BigInteger.ONE.shiftLeft(252) + BigInteger("27742317777372353535851937790883648493")
        private val D: BigInteger = BigInteger.valueOf(-121665).mod(P) * BigInteger.valueOf(121666).modInverse(P) % P
        private val D2: BigInteger = D.shiftLeft(1).mod(P)
        private val SQRT_M1: BigInteger = BigInteger.TWO.modPow((P - BigInteger.ONE).shiftRight(2), P)

        class Pt(val x: BigInteger, val y: BigInteger, val z: BigInteger, val t: BigInteger)

        val ZERO = Pt(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)
        val B: Pt = decode(hex("5866666666666666666666666666666666666666666666666666666666666666"))!!
        val M: Pt = decode(hex("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e"))!!
        val N: Pt = decode(hex("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778"))!!

        fun add(p: Pt, q: Pt): Pt {
            val a = (p.y - p.x) * (q.y - q.x) % P
            val b = (p.y + p.x) * (q.y + q.x) % P
            val c = p.t * D2 % P * q.t % P
            val d = p.z.shiftLeft(1) * q.z % P
            val e = b - a; val f = d - c; val g = d + c; val h = b + a
            return Pt((e * f).mod(P), (g * h).mod(P), (f * g).mod(P), (e * h).mod(P))
        }

        fun neg(p: Pt) = Pt((P - p.x).mod(P), p.y, p.z, (P - p.t).mod(P))

        fun mul(p: Pt, k: BigInteger): Pt {
            var r = ZERO
            for (i in k.bitLength() - 1 downTo 0) {
                r = add(r, r)
                if (k.testBit(i)) r = add(r, p)
            }
            return r
        }

        fun encode(p: Pt): ByteArray {
            val zi = p.z.modInverse(P)
            val x = p.x * zi % P
            val y = p.y * zi % P
            val out = ByteArray(32)
            val yb = y.toByteArray().reversedArray()
            for (i in 0 until minOf(32, yb.size)) out[i] = yb[i]
            if (x.testBit(0)) out[31] = (out[31].toInt() or 0x80).toByte()
            return out
        }

        fun decode(s: ByteArray): Pt? {
            val sign = (s[31].toInt() ushr 7) and 1
            val yb = s.copyOf().also { it[31] = (it[31].toInt() and 0x7F).toByte() }
            val y = BigInteger(1, yb.reversedArray())
            if (y >= P) return null
            val y2 = y * y % P
            val u = (y2 - BigInteger.ONE).mod(P)
            val v = (D * y2 + BigInteger.ONE) % P
            val x2 = u * v.modInverse(P) % P
            var x = x2.modPow((P + BigInteger.valueOf(3)).shiftRight(3), P)
            if ((x * x - x2).mod(P).signum() != 0) x = x * SQRT_M1 % P
            if ((x * x - x2).mod(P).signum() != 0) return null
            if ((if (x.testBit(0)) 1 else 0) != sign) x = (P - x).mod(P)
            return Pt(x, y, BigInteger.ONE, x * y % P)
        }

        private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }
}
