package com.keshav.daptap.nfc

import android.content.Context
import com.keshav.daptap.core.SnapBlobs
import com.keshav.daptap.core.SnapIdentity
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.util.UUID

/**
 * DapTap's NameDrop identity: a P-256 key pair (the public half goes to the iPhone in the handshake and is
 * the key the contact exchange's TLS certificate must carry) and a Bonjour listener UUID. Minted once per
 * install and kept in app-private storage, because the NFC half and the network half must agree on it.
 */
object SnapKeys {
    private const val FILE = "snap-identity.bin"
    private const val VERSION = 1

    @Volatile private var cached: Pair<SnapIdentity, SnapBlobs>? = null

    /** The identity and its prebuilt answers. Mints on first use; call [warm] early so the HCE path never does. */
    fun get(context: Context): Pair<SnapIdentity, SnapBlobs> {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val file = File(context.filesDir, FILE)
            val id = runCatching { read(file) }.getOrNull() ?: mint().also { (id, pkcs8) -> write(file, id, pkcs8) }.first
            return (id to SnapBlobs(id)).also { cached = it }
        }
    }

    fun warm(context: Context) { runCatching { get(context) } }

    /** The private key (PKCS#8 DER) for the TLS side of the exchange. */
    fun privateKey(context: Context): ByteArray? = runCatching {
        get(context)
        readRaw(File(context.filesDir, FILE))?.second
    }.getOrNull()

    private fun mint(): Pair<SnapIdentity, ByteArray> {
        val random = SecureRandom()
        val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }.generateKeyPair()
        val spki = kp.public.encoded
        val uuid = UUID.randomUUID().let { u ->
            ByteBuffer.allocate(16).putLong(u.mostSignificantBits).putLong(u.leastSignificantBits).array()
        }
        val token = ByteArray(6).also { random.nextBytes(it) }
        // Field 4 is signed with a key that never crosses the air, so no peer can check it during the
        // handshake. A real Ed25519 signature where the platform has one; otherwise well-formed random bytes.
        val message = spki + uuid + token
        val signature = runCatching {
            val ed = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
            java.security.Signature.getInstance("Ed25519").run { initSign(ed.private); update(message); sign() }
        }.getOrNull()?.takeIf { it.size == 64 } ?: ByteArray(64).also { random.nextBytes(it) }
        return SnapIdentity(spki, uuid, token, signature) to kp.private.encoded
    }

    private fun write(file: File, id: SnapIdentity, pkcs8: ByteArray) {
        val tmp = File(file.parentFile, "$FILE.tmp")
        java.io.DataOutputStream(tmp.outputStream()).use { out ->
            out.writeInt(VERSION)
            for (b in listOf(id.spki, id.listenerUuid, id.token, id.signature, pkcs8)) { out.writeInt(b.size); out.write(b) }
        }
        tmp.renameTo(file)
    }

    private fun readRaw(file: File): Pair<SnapIdentity, ByteArray>? {
        if (!file.exists()) return null
        java.io.DataInputStream(file.inputStream()).use { input ->
            if (input.readInt() != VERSION) return null
            val parts = List(5) { ByteArray(input.readInt()).also { input.readFully(it) } }
            return SnapIdentity(parts[0], parts[1], parts[2], parts[3]) to parts[4]
        }
    }

    private fun read(file: File): SnapIdentity? = readRaw(file)?.first
}
