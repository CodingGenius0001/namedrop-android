package com.keshav.daptap.namedrop

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The TLS certificate for the NameDrop exchange. iOS accepts it because its public key is the P-256 key we
 * handed over as SNAP key 1 during the NFC handshake; nothing about it is checked against Apple trust. A real
 * iPhone's leaf has an empty subject, but Java and Android refuse to parse an empty issuer, so both are
 * CN=<hostname>. Built as raw DER so no X.509 library is needed.
 */
object SelfCert {
    fun build(spki: ByteArray, privateKey: PrivateKey, hostname: String, now: Long = System.currentTimeMillis()): ByteArray {
        val serial = ByteArray(16).also { SecureRandom().nextBytes(it); it[0] = (it[0].toInt() and 0x7F).toByte() }
        val rsa = privateKey.algorithm.equals("RSA", true)
        // ecdsa-with-SHA256 (no parameters) or sha256WithRSAEncryption (NULL parameters).
        val sigAlg = if (rsa) seq(oid(0x2A, 0x86, 0x48, 0x86, 0xF7, 0x0D, 0x01, 0x01, 0x0B), byteArrayOf(0x05, 0x00))
            else seq(oid(0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x04, 0x03, 0x02))
        val extensions = seq(
            ext(oid(0x55, 0x1D, 0x13), true, seq()), // basicConstraints: CA false
            ext(oid(0x55, 0x1D, 0x0F), true, tlv(0x03, byteArrayOf(0x05, 0xA0.toByte()))), // keyUsage: digitalSignature, keyEncipherment
            ext(oid(0x55, 0x1D, 0x25), false, seq(
                oid(0x2B, 0x06, 0x01, 0x05, 0x05, 0x07, 0x03, 0x01), // serverAuth
                oid(0x2B, 0x06, 0x01, 0x05, 0x05, 0x07, 0x03, 0x02), // clientAuth
            )),
            ext(oid(0x55, 0x1D, 0x11), false, seq(tlv(0x82, hostname.toByteArray(Charsets.US_ASCII)))),
        )
        val tbs = seq(
            tlv(0xA0, tlv(0x02, byteArrayOf(2))), // v3
            tlv(0x02, BigInteger(1, serial).toByteArray()),
            sigAlg,
            name(hostname),
            seq(utc(now - DAY), utc(now + 10 * 365 * DAY)),
            name(hostname),
            spki,
            tlv(0xA3, extensions),
        )
        val sig = Signature.getInstance(if (rsa) "SHA256withRSA" else "SHA256withECDSA").run { initSign(privateKey); update(tbs); sign() }
        return seq(tbs, sigAlg, tlv(0x03, byteArrayOf(0) + sig))
    }

    private const val DAY = 86_400_000L

    private fun utc(t: Long) = tlv(0x17, SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(t)).toByteArray(Charsets.US_ASCII))

    private fun ext(id: ByteArray, critical: Boolean, value: ByteArray) =
        seq(id, *(if (critical) arrayOf(tlv(0x01, byteArrayOf(0xFF.toByte()))) else emptyArray()), tlv(0x04, value))

    /** Name ::= SEQUENCE { SET { SEQUENCE { id-at-commonName, UTF8String } } } */
    private fun name(cn: String) = seq(tlv(0x31, seq(oid(0x55, 0x04, 0x03), tlv(0x0C, cn.toByteArray(Charsets.UTF_8)))))

    private fun oid(vararg b: Int) = tlv(0x06, ByteArray(b.size) { b[it].toByte() })
    private fun seq(vararg parts: ByteArray) = tlv(0x30, parts.fold(ByteArray(0)) { a, p -> a + p })

    private fun tlv(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val n = content.size
        when {
            n < 0x80 -> out.write(n)
            n < 0x100 -> { out.write(0x81); out.write(n) }
            n < 0x10000 -> { out.write(0x82); out.write(n ushr 8); out.write(n and 0xFF) }
            else -> { out.write(0x83); out.write(n ushr 16); out.write((n ushr 8) and 0xFF); out.write(n and 0xFF) }
        }
        out.write(content)
        return out.toByteArray()
    }
}
