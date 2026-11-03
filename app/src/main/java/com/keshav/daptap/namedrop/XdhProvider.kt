package com.keshav.daptap.namedrop

import android.os.Build
import androidx.annotation.RequiresApi
import java.math.BigInteger
import java.security.InvalidKeyException
import java.security.Key
import java.security.KeyFactorySpi
import java.security.KeyPair
import java.security.KeyPairGeneratorSpi
import java.security.PrivateKey
import java.security.Provider
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Security
import java.security.interfaces.XECPrivateKey
import java.security.interfaces.XECPublicKey
import java.security.spec.AlgorithmParameterSpec
import java.security.spec.InvalidKeySpecException
import java.security.spec.KeySpec
import java.security.spec.NamedParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.security.spec.XECPrivateKeySpec
import java.security.spec.XECPublicKeySpec
import java.util.Optional
import javax.crypto.KeyAgreementSpi
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * A JCA provider for X25519 under the standard Java names ("XDH" with NamedParameterSpec / XECPublicKeySpec),
 * which kwik's TLS (agent15) uses and Android's Conscrypt doesn't offer. Installed first, only for "XDH".
 */
@RequiresApi(33)
@Suppress("DEPRECATION")
class XdhProvider : Provider("DapTapXDH", 1.0, "X25519 for the NameDrop exchange") {
    init {
        put("KeyPairGenerator.XDH", Kpg::class.java.name)
        put("KeyPairGenerator.X25519", Kpg::class.java.name)
        put("KeyFactory.XDH", Kf::class.java.name)
        put("KeyFactory.X25519", Kf::class.java.name)
        put("KeyAgreement.XDH", Ka::class.java.name)
        put("KeyAgreement.X25519", Ka::class.java.name)
    }

    class Pub(private val raw: ByteArray) : XECPublicKey {
        override fun getU(): BigInteger = X25519.decodeU(raw)
        override fun getParams(): AlgorithmParameterSpec = NamedParameterSpec.X25519
        override fun getAlgorithm() = "XDH"
        override fun getFormat() = "X.509"
        override fun getEncoded(): ByteArray = SPKI_PREFIX + raw
        fun raw(): ByteArray = raw.copyOf()
    }

    class Priv(private val scalar: ByteArray) : XECPrivateKey {
        override fun getScalar(): Optional<ByteArray> = Optional.of(scalar.copyOf())
        override fun getParams(): AlgorithmParameterSpec = NamedParameterSpec.X25519
        override fun getAlgorithm() = "XDH"
        override fun getFormat() = "PKCS#8"
        override fun getEncoded(): ByteArray = PKCS8_PREFIX + scalar
    }

    class Kpg : KeyPairGeneratorSpi() {
        private var random = SecureRandom()
        override fun initialize(keysize: Int, random: SecureRandom?) { if (keysize != 255) throw java.security.InvalidParameterException("X25519 only"); random?.let { this.random = it } }
        override fun initialize(params: AlgorithmParameterSpec?, random: SecureRandom?) {
            if (params != null && !(params is NamedParameterSpec && params.name.equals("X25519", true))) {
                throw java.security.InvalidAlgorithmParameterException("X25519 only")
            }
            random?.let { this.random = it }
        }
        override fun generateKeyPair(): KeyPair {
            val k = ByteArray(32).also { random.nextBytes(it) }
            return KeyPair(Pub(X25519.publicKey(k)), Priv(k))
        }
    }

    class Kf : KeyFactorySpi() {
        override fun engineGeneratePublic(spec: KeySpec?): PublicKey = when (spec) {
            is XECPublicKeySpec -> Pub(X25519.encodeU(spec.u.mod(BigInteger.ONE.shiftLeft(255))))
            is X509EncodedKeySpec -> spec.encoded.let {
                if (it.size != 44 || !it.copyOf(12).contentEquals(SPKI_PREFIX)) throw InvalidKeySpecException("not an X25519 key")
                Pub(it.copyOfRange(12, 44))
            }
            else -> throw InvalidKeySpecException("unsupported spec $spec")
        }
        override fun engineGeneratePrivate(spec: KeySpec?): PrivateKey = when (spec) {
            is XECPrivateKeySpec -> Priv(spec.scalar)
            is PKCS8EncodedKeySpec -> spec.encoded.let {
                if (it.size != 48 || !it.copyOf(16).contentEquals(PKCS8_PREFIX)) throw InvalidKeySpecException("not an X25519 key")
                Priv(it.copyOfRange(16, 48))
            }
            else -> throw InvalidKeySpecException("unsupported spec $spec")
        }
        override fun <T : KeySpec?> engineGetKeySpec(key: Key?, keySpec: Class<T>?): T = throw InvalidKeySpecException("unsupported")
        override fun engineTranslateKey(key: Key?): Key = when (key) {
            is Pub, is Priv -> key
            is XECPublicKey -> Pub(X25519.encodeU(key.u))
            is XECPrivateKey -> Priv(key.scalar.orElseThrow { InvalidKeyException("no scalar") })
            else -> throw InvalidKeyException("not an XDH key")
        }
    }

    class Ka : KeyAgreementSpi() {
        private var scalar: ByteArray? = null
        private var secret: ByteArray? = null
        override fun engineInit(key: Key?, random: SecureRandom?) { scalar = scalarOf(key); secret = null }
        override fun engineInit(key: Key?, params: AlgorithmParameterSpec?, random: SecureRandom?) = engineInit(key, random)
        override fun engineDoPhase(key: Key?, lastPhase: Boolean): Key? {
            val k = scalar ?: throw IllegalStateException("not initialised")
            val u = when (key) {
                is Pub -> key.raw()
                is XECPublicKey -> X25519.encodeU(key.u)
                else -> throw InvalidKeyException("not an XDH public key")
            }
            val s = X25519.scalarMult(k, u)
            if (s.all { it.toInt() == 0 }) throw InvalidKeyException("low-order point")
            secret = s
            return null
        }
        override fun engineGenerateSecret(): ByteArray = checkNotNull(secret).also { secret = null }
        override fun engineGenerateSecret(sharedSecret: ByteArray, offset: Int): Int {
            val s = engineGenerateSecret(); s.copyInto(sharedSecret, offset); return s.size
        }
        override fun engineGenerateSecret(algorithm: String): SecretKey = SecretKeySpec(engineGenerateSecret(), algorithm)

        private fun scalarOf(key: Key?): ByteArray = when (key) {
            is XECPrivateKey -> key.scalar.orElseThrow { InvalidKeyException("no scalar") }
            else -> throw InvalidKeyException("not an XDH private key")
        }
    }

    companion object {
        /** SubjectPublicKeyInfo header for id-X25519 (1.3.101.110) with a 32-byte key. */
        val SPKI_PREFIX = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x6e, 0x03, 0x21, 0x00)
        val PKCS8_PREFIX = byteArrayOf(0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x6e, 0x04, 0x22, 0x04, 0x20)

        /** Puts this provider first when the platform's XDH can't take the standard specs (Android). */
        fun installIfNeeded(): String {
            if (Build.VERSION.SDK_INT < 33) return "unsupported Android"
            if (Security.getProvider("DapTapXDH") != null) return "installed"
            val nativeOk = runCatching {
                java.security.KeyFactory.getInstance("XDH")
                    .generatePublic(XECPublicKeySpec(NamedParameterSpec.X25519, BigInteger.valueOf(9)))
                java.security.KeyPairGenerator.getInstance("XDH").initialize(NamedParameterSpec.X25519)
            }.isSuccess
            if (nativeOk) return "platform"
            Security.insertProviderAt(XdhProvider(), 1)
            return "installed"
        }
    }
}
