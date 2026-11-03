package com.keshav.daptap.applink

import android.content.Context
import android.net.ssl.SSLSockets
import android.os.Build
import androidx.annotation.RequiresApi
import com.keshav.daptap.namedrop.SelfCert
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/**
 * A minimal client for the phone's own Wireless debugging (adbd over TLS): pair once with the 6-digit code,
 * then connect and run a shell command. That shell is what starts DapTap's link helper (see LinkHelper).
 * Protocol as in AOSP packages/modules/adb (pairing_auth, pairing_connection, transport TLS).
 */
@RequiresApi(Build.VERSION_CODES.R)
class Adb(private val context: Context) {
    private val keyFile = File(context.filesDir, "adb-key.bin")

    /** DapTap's adb identity: an RSA-2048 key and a self-signed certificate, made once. */
    private val identity: Pair<PrivateKey, X509Certificate> by lazy { loadOrCreate() }

    private fun loadOrCreate(): Pair<PrivateKey, X509Certificate> {
        runCatching {
            DataInputStream(keyFile.inputStream().buffered()).use { inp ->
                val k = ByteArray(inp.readInt()).also(inp::readFully)
                val c = ByteArray(inp.readInt()).also(inp::readFully)
                val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(k))
                val cert = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(c)) as X509Certificate
                return key to cert
            }
        }
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val der = SelfCert.build(kp.public.encoded, kp.private, "DapTap")
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        val tmp = File(keyFile.path + ".tmp")
        java.io.DataOutputStream(tmp.outputStream().buffered()).use { o ->
            kp.private.encoded.let { o.writeInt(it.size); o.write(it) }
            der.let { o.writeInt(it.size); o.write(it) }
        }
        tmp.renameTo(keyFile)
        return kp.private to cert
    }

    private fun sslContext(): SSLContext = SSLContext.getInstance("TLS").apply {
        val (key, cert) = identity
        init(arrayOf(object : X509ExtendedKeyManager() {
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf("adb")
            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = "adb"
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = null
            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = null
            override fun getCertificateChain(alias: String?) = arrayOf(cert)
            override fun getPrivateKey(alias: String?) = key
        }), arrayOf(object : X509TrustManager {
            // adbd's certificate is self-signed; the pairing code (SPAKE2 bound to this TLS session) is the trust.
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
        }), SecureRandom())
    }

    private fun tls(host: String, port: Int, raw: Socket? = null): SSLSocket {
        val plain = raw ?: Socket().apply { connect(InetSocketAddress(host, port), 5_000); soTimeout = 15_000 }
        plain.tcpNoDelay = true
        return (sslContext().socketFactory.createSocket(plain, host, port, true) as SSLSocket).apply {
            enabledProtocols = arrayOf("TLSv1.3")
            useClientMode = true
            startHandshake()
        }
    }

    /** Pairs with Wireless debugging's "Pair device with pairing code" dialog. */
    fun pair(host: String, port: Int, code: String) {
        tls(host, port).use { s ->
            val exported = SSLSockets.exportKeyingMaterial(s, "adb-label\u0000", null, 64)
                ?: error("TLS keying material unavailable")
            val spake = Spake2(true, CLIENT_NAME, SERVER_NAME)
            val out = s.outputStream
            val inp = DataInputStream(s.inputStream)
            packet(out, 0, spake.generateMessage(code.trim().toByteArray() + exported))
            val theirs = readPacket(inp, 0)
            val shared = spake.processMessage(theirs) ?: error("pairing message rejected")
            val aesKey = at.favre.lib.hkdf.HKDF.fromHmacSha256().extractAndExpand(null as ByteArray?, shared, HKDF_INFO, 16)

            val info = ByteArray(PEER_INFO_SIZE)
            info[0] = 0 // ADB_RSA_PUB_KEY
            val pub = publicKeyLine()
            pub.copyInto(info, 1, 0, minOf(pub.size, PEER_INFO_SIZE - 1))
            packet(out, 1, gcm(Cipher.ENCRYPT_MODE, aesKey, 0, info))
            val reply = readPacket(inp, 1)
            // A wrong code makes the two sides derive different keys, so this decryption fails.
            runCatching { gcm(Cipher.DECRYPT_MODE, aesKey, 0, reply) }.getOrElse { error("wrong pairing code") }
        }
    }

    /** Runs [command] through adbd's shell service and returns what it printed (until the command exits). */
    fun shell(host: String, port: Int, command: String, timeoutMs: Int = 15_000): String {
        val raw = Socket().apply { connect(InetSocketAddress(host, port), 5_000); soTimeout = timeoutMs }
        raw.use {
            val ro = raw.outputStream
            val ri = raw.inputStream
            ro.write(message(A_CNXN, A_VERSION, MAX_DATA, "host::\u0000".toByteArray()))
            val first = readMessage(ri)
            if (first.command != A_STLS) error("adbd didn't offer TLS (got ${cmdName(first.command)})")
            ro.write(message(A_STLS, A_STLS_VERSION, 0, null))
            val s = tls(host, port, raw)
            val o = s.outputStream
            val i = s.inputStream
            var m = readMessage(i)
            if (m.command != A_CNXN) error("adbd refused us (${cmdName(m.command)}); pair again")
            val localId = 1
            o.write(message(A_OPEN, localId, 0, ("shell:" + command + "\u0000").toByteArray()))
            val output = ByteArrayOutputStream()
            var remoteId = 0
            while (true) {
                m = runCatching { readMessage(i) }.getOrNull() ?: break
                when (m.command) {
                    A_OKAY -> remoteId = m.arg0
                    A_WRTE -> { output.write(m.payload); o.write(message(A_OKAY, localId, m.arg0, null)) }
                    A_CLSE -> { o.write(message(A_CLSE, localId, remoteId, null)); break }
                }
            }
            runCatching { s.close() }
            return output.toString()
        }
    }

    /** `base64(android_pubkey) name\0`, the line adbd stores in adb_keys. */
    private fun publicKeyLine(): ByteArray {
        val pub = identity.second.publicKey as RSAPublicKey
        val n = pub.modulus
        val words = 2048 / 32
        val b = ByteBuffer.allocate(3 * 4 + 2 * 256).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(words)
        val r32 = BigInteger.ONE.shiftLeft(32)
        b.putInt((r32 - n.mod(r32).modInverse(r32)).toInt()) // n0inv = -1/n mod 2^32
        b.put(le(n, 256))
        b.put(le(BigInteger.ONE.shiftLeft(2048).modPow(BigInteger.TWO, n), 256)) // rr = (2^2048)^2 mod n
        b.putInt(pub.publicExponent.toInt())
        val name = "daptap@${Build.MODEL.replace(' ', '_')}"
        return android.util.Base64.encode(b.array(), android.util.Base64.NO_WRAP) + " $name\u0000".toByteArray()
    }

    private fun le(v: BigInteger, len: Int): ByteArray {
        val be = v.toByteArray()
        return ByteArray(len) { i -> if (i < be.size) be[be.size - 1 - i] else 0 }
    }

    private fun gcm(mode: Int, key: ByteArray, counter: Long, data: ByteArray): ByteArray {
        val iv = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putLong(counter).array()
        return Cipher.getInstance("AES/GCM/NoPadding").run { init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv)); doFinal(data) }
    }

    private fun packet(out: OutputStream, type: Int, payload: ByteArray) {
        out.write(ByteBuffer.allocate(6).put(1).put(type.toByte()).putInt(payload.size).array() + payload)
        out.flush()
    }

    private fun readPacket(inp: DataInputStream, type: Int): ByteArray {
        val h = ByteArray(6).also(inp::readFully)
        val bb = ByteBuffer.wrap(h)
        val version = bb.get().toInt(); val t = bb.get().toInt(); val len = bb.getInt()
        if (version != 1 || t != type || len <= 0 || len > 2 * PEER_INFO_SIZE) error("unexpected pairing packet (v$version type $t len $len)")
        return ByteArray(len).also(inp::readFully)
    }

    private class Msg(val command: Int, val arg0: Int, val arg1: Int, val payload: ByteArray)

    private fun message(cmd: Int, a0: Int, a1: Int, data: ByteArray?): ByteArray {
        val d = data ?: ByteArray(0)
        return ByteBuffer.allocate(24 + d.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(cmd).putInt(a0).putInt(a1).putInt(d.size).putInt(d.sumOf { it.toInt() and 0xFF }).putInt(cmd.inv())
            .put(d).array()
    }

    private fun readMessage(inp: InputStream): Msg {
        val h = readN(inp, 24)
        val bb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = bb.getInt(); val a0 = bb.getInt(); val a1 = bb.getInt(); val len = bb.getInt(); bb.getInt(); val magic = bb.getInt()
        if (magic != cmd.inv() || len < 0 || len > MAX_DATA) error("bad adb message")
        return Msg(cmd, a0, a1, readN(inp, len))
    }

    private fun readN(inp: InputStream, n: Int): ByteArray {
        val b = ByteArray(n); var off = 0
        while (off < n) { val r = inp.read(b, off, n - off); if (r < 0) error("adbd closed the connection"); off += r }
        return b
    }

    private fun cmdName(c: Int) = String(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(c).array())

    private companion object {
        val CLIENT_NAME = "adb pair client\u0000".toByteArray()
        val SERVER_NAME = "adb pair server\u0000".toByteArray()
        val HKDF_INFO = "adb pairing_auth aes-128-gcm key".toByteArray()
        const val PEER_INFO_SIZE = 1 shl 13
        const val A_CNXN = 0x4e584e43
        const val A_OPEN = 0x4e45504f
        const val A_OKAY = 0x59414b4f
        const val A_CLSE = 0x45534c43
        const val A_WRTE = 0x45545257
        const val A_STLS = 0x534c5453
        const val A_VERSION = 0x01000001
        const val A_STLS_VERSION = 0x01000000
        const val MAX_DATA = 1024 * 1024
    }
}
