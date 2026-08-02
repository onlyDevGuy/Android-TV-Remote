package com.sizwe.tvremote.adb

import android.util.Base64
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

/**
 * The RSA half of the ADB handshake.
 *
 * When `adbd` challenges us with `AUTH TOKEN` it sends 20 random bytes. We answer with
 * `AUTH SIGNATURE` containing those bytes RSA-signed with our private key. If the daemon has never
 * seen our public key it rejects the signature and challenges again; we then send
 * `AUTH RSAPUBLICKEY`, which is what makes the TV pop up "Allow debugging from this computer?".
 *
 * Two details are easy to get wrong and both are load-bearing:
 *
 *  1. The signature is *not* a standard `SHA1withRSA` signature. adbd does a raw RSA operation over
 *     a fixed PKCS#1 v1.5 pad concatenated with the raw token, so we sign with `RSA/ECB/NoPadding`
 *     and supply [SIGNATURE_PADDING] ourselves.
 *  2. The public key is not X.509. adbd wants Android's own `RSAPublicKey` struct - little-endian
 *     modulus, a Montgomery `n0inv`, and `rr = (2^2048)^2 mod n` - base64'd, with a trailing
 *     "user@host" comment.
 */
object AdbCrypto {

    private const val KEY_SIZE_BITS = 2048
    private const val KEY_SIZE_BYTES = KEY_SIZE_BITS / 8
    private const val KEY_SIZE_WORDS = KEY_SIZE_BYTES / 4

    /** Anything is accepted here; it is only shown in the TV's "allow debugging" dialog. */
    private const val KEY_COMMENT = "tv-remote@android"

    /**
     * PKCS#1 v1.5 pad for a SHA-1 digest in a 2048-bit RSA block:
     * `00 01 FF*218 00` followed by the 15-byte ASN.1 DigestInfo prefix for SHA-1.
     * 236 bytes + the 20-byte token = 256 bytes = one RSA block.
     */
    private val SIGNATURE_PADDING: ByteArray = buildSignaturePadding()

    fun generateKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(KEY_SIZE_BITS) }.generateKeyPair()

    fun decodeKeyPair(privateKeyBytes: ByteArray, publicKeyBytes: ByteArray): KeyPair {
        val factory = KeyFactory.getInstance("RSA")
        val privateKey = factory.generatePrivate(PKCS8EncodedKeySpec(privateKeyBytes))
        val publicKey = factory.generatePublic(X509EncodedKeySpec(publicKeyBytes))
        return KeyPair(publicKey, privateKey)
    }

    /**
     * Signs the daemon's auth token. [token] is normally 20 bytes; anything longer than
     * `256 - padding` would overflow the RSA block, so it is rejected loudly rather than truncated.
     */
    fun signToken(privateKey: RSAPrivateKey, token: ByteArray): ByteArray {
        require(token.size <= KEY_SIZE_BYTES - SIGNATURE_PADDING.size) {
            "Auth token too long: ${token.size} bytes"
        }
        val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, privateKey)
        cipher.update(SIGNATURE_PADDING)
        return cipher.doFinal(token)
    }

    /** Base64 of Android's `RSAPublicKey` struct plus the comment adbd expects. */
    fun encodePublicKey(publicKey: RSAPublicKey): ByteArray {
        val struct = androidPublicKeyStruct(publicKey)
        val base64 = Base64.encodeToString(struct, Base64.NO_WRAP)
        return "$base64 $KEY_COMMENT".toByteArray(Charsets.UTF_8)
    }

    /**
     * ```c
     * struct RSAPublicKey {
     *     uint32_t modulus_size_words;   // 64
     *     uint32_t n0inv;                // -1 / n[0] mod 2^32
     *     uint8_t  modulus[256];         // little-endian
     *     uint8_t  rr[256];              // (2^2048)^2 mod n, little-endian
     *     uint32_t exponent;             // 65537
     * };
     * ```
     */
    private fun androidPublicKeyStruct(publicKey: RSAPublicKey): ByteArray {
        val modulus = publicKey.modulus
        val r32 = BigInteger.ZERO.setBit(32)

        // n0inv = -1 / n mod 2^32
        val n0inv = modulus.mod(r32).modInverse(r32).negate().mod(r32)

        // rr = (2^(key bits))^2 mod n
        val rr = BigInteger.ZERO.setBit(KEY_SIZE_BITS).pow(2).mod(modulus)

        val buffer = ByteBuffer.allocate(4 + 4 + KEY_SIZE_BYTES + KEY_SIZE_BYTES + 4)
            .order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(KEY_SIZE_WORDS)
        buffer.putInt(n0inv.toInt())
        buffer.put(toLittleEndianFixed(modulus, KEY_SIZE_BYTES))
        buffer.put(toLittleEndianFixed(rr, KEY_SIZE_BYTES))
        buffer.putInt(publicKey.publicExponent.toInt())
        return buffer.array()
    }

    /**
     * BigInteger hands back a big-endian two's-complement array that may carry a leading zero sign
     * byte or be shorter than the key size. Normalise to exactly [size] little-endian bytes.
     */
    private fun toLittleEndianFixed(value: BigInteger, size: Int): ByteArray {
        val bigEndian = value.toByteArray()
        val magnitude = when {
            bigEndian.size == size -> bigEndian
            bigEndian.size == size + 1 && bigEndian[0] == 0.toByte() -> bigEndian.copyOfRange(1, bigEndian.size)
            bigEndian.size < size -> ByteArray(size - bigEndian.size) + bigEndian
            else -> error("Value is ${bigEndian.size} bytes, expected at most $size")
        }
        return magnitude.reversedArray()
    }

    private fun buildSignaturePadding(): ByteArray {
        val sha1DigestInfo = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
            0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
        )
        val digestLength = 20
        val total = KEY_SIZE_BYTES - digestLength // 236
        val padding = ByteArray(total)
        padding[0] = 0x00
        padding[1] = 0x01
        val ffCount = total - 3 - sha1DigestInfo.size
        for (i in 0 until ffCount) padding[2 + i] = 0xFF.toByte()
        padding[2 + ffCount] = 0x00
        sha1DigestInfo.copyInto(padding, 3 + ffCount)
        return padding
    }
}
