package dev.rwilco.vault

import kotlinx.serialization.Serializable
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The container version: the crypto and the shape of the envelope, not the data inside. */
const val VAULT_FORMAT = 1

/** PBKDF2-HMAC-SHA256 rounds; what OWASP asks for, one to three seconds on a phone. */
const val KDF_ITERATIONS = 600_000

/** Why a vault could not be opened. Three answers, all honest, none of them a guess. */
sealed class VaultException(message: String) : Exception(message) {
    /** Or a damaged header: the two cannot be told apart, and the message says so. */
    class WrongPassphrase : VaultException("wrong passphrase, or the header is damaged")
    class Corrupt(detail: String) : VaultException(detail)
    /** A container or data version this build does not know. Update the app; never guess. */
    class NewerThanThisApp(detail: String) : VaultException(detail)
}

/** What can be read without the key: enough to derive it. */
class VaultHeader(val format: Int, val salt: ByteArray, val iterations: Int)

/**
 * The file that leaves the phone. One JSON document: a plaintext header with the KDF parameters,
 * and two AES-256-GCM boxes under the same key — a tiny [check] that opens first so a wrong
 * passphrase fails cleanly, and the [payload], the gzipped snapshot. Nothing else is readable
 * without the passphrase: what the remote learns is the size and when it was written.
 */
@Serializable
private data class Envelope(
    val rwilco: String = MAGIC,
    val format: Int = VAULT_FORMAT,
    val kdf: Kdf,
    val cipher: String = CIPHER_NAME,
    val check: Box,
    val payload: Box,
)

@Serializable
private data class Kdf(val alg: String = KDF_NAME, val iterations: Int, val salt: String)

@Serializable
private data class Box(val nonce: String, val data: String)

private const val MAGIC = "vault"
private const val KDF_NAME = "pbkdf2-hmac-sha256"
private const val CIPHER_NAME = "aes-256-gcm"
private const val CHECK_PLAINTEXT = "rwilco"
private const val SALT_BYTES = 16
private const val NONCE_BYTES = 12
private const val KEY_BYTES = 32
private const val TAG_BITS = 128

object VaultCrypto {

    /**
     * The key for a passphrase. The passphrase is normalised (NFC) before it is bytes, because
     * "ñ" can arrive from a keyboard as one code point or as two and both are the same word;
     * and the derivation is this object's own (see [pbkdf2HmacSha256]) rather than a
     * provider's, so the bytes it hashes are the same on the test JVM and on the phone.
     */
    fun deriveKey(passphrase: String, salt: ByteArray, iterations: Int = KDF_ITERATIONS): ByteArray {
        val password = Normalizer.normalize(passphrase, Normalizer.Form.NFC).toByteArray(Charsets.UTF_8)
        require(password.isNotEmpty()) { "empty passphrase" }
        return pbkdf2HmacSha256(password, salt, iterations, KEY_BYTES)
    }

    /** RFC 8018 §5.2 over HMAC-SHA256, a dozen lines so there is nothing to trust but the HMAC. */
    fun pbkdf2HmacSha256(password: ByteArray, salt: ByteArray, iterations: Int, length: Int): ByteArray {
        require(iterations >= 1) { "iterations" }
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(password, "HmacSHA256")) }
        val hashLength = mac.macLength
        val blocks = (length + hashLength - 1) / hashLength
        val out = ByteArray(blocks * hashLength)
        for (block in 1..blocks) {
            mac.update(salt)
            mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            var u = mac.doFinal()
            val t = u.copyOf()
            repeat(iterations - 1) {
                u = mac.doFinal(u)
                for (i in t.indices) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
            }
            t.copyInto(out, (block - 1) * hashLength)
        }
        return out.copyOf(length)
    }

    fun newSalt(random: SecureRandom = SecureRandom()): ByteArray = ByteArray(SALT_BYTES).also(random::nextBytes)

    /** The envelope for [plain] under [key]; [salt] and [iterations] travel in the header so a new phone can derive it. */
    fun seal(plain: ByteArray, key: ByteArray, salt: ByteArray, iterations: Int, random: SecureRandom = SecureRandom()): ByteArray {
        val envelope = Envelope(
            kdf = Kdf(iterations = iterations, salt = b64(salt)),
            check = encrypt(CHECK_PLAINTEXT.toByteArray(Charsets.UTF_8), key, random),
            payload = encrypt(gzip(plain), key, random),
        )
        return vaultJson.encodeToString(Envelope.serializer(), envelope).toByteArray(Charsets.UTF_8)
    }

    /** The header of an envelope: what a restore needs before it has a key. */
    fun header(envelope: ByteArray): VaultHeader = parse(envelope).let { VaultHeader(it.format, unb64(it.kdf.salt), it.kdf.iterations) }

    /** The snapshot bytes inside [envelope], or a [VaultException] that says exactly why not. */
    fun open(envelope: ByteArray, key: ByteArray): ByteArray {
        val parsed = parse(envelope)
        val check = runCatching { decrypt(parsed.check, key) }.getOrNull()
        if (check == null || !check.contentEquals(CHECK_PLAINTEXT.toByteArray(Charsets.UTF_8))) throw VaultException.WrongPassphrase()
        val packed = runCatching { decrypt(parsed.payload, key) }.getOrElse { throw VaultException.Corrupt("payload does not open") }
        return runCatching { gunzip(packed) }.getOrElse { throw VaultException.Corrupt("payload does not unpack") }
    }

    /** `git hash-object`: what GitHub reports as the blob sha, computable here before the upload. */
    fun gitBlobSha(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update("blob ${bytes.size}\u0000".toByteArray(Charsets.UTF_8))
        return digest.digest(bytes).toHex()
    }

    fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun parse(envelope: ByteArray): Envelope {
        val parsed = runCatching { vaultJson.decodeFromString(Envelope.serializer(), String(envelope, Charsets.UTF_8)) }
            .getOrElse { throw VaultException.Corrupt("not a vault") }
        if (parsed.rwilco != MAGIC) throw VaultException.Corrupt("not a vault")
        if (parsed.format > VAULT_FORMAT) throw VaultException.NewerThanThisApp("container version ${parsed.format}")
        if (parsed.kdf.alg != KDF_NAME || parsed.cipher != CIPHER_NAME) throw VaultException.NewerThanThisApp("${parsed.kdf.alg}/${parsed.cipher}")
        if (parsed.kdf.iterations < 1) throw VaultException.Corrupt("iterations")
        return parsed
    }

    /** Bound to the container version, so a header cannot be swapped under a payload. */
    private fun aad(): ByteArray = "rwilco.vault:$VAULT_FORMAT".toByteArray(Charsets.UTF_8)

    private fun encrypt(plain: ByteArray, key: ByteArray, random: SecureRandom): Box {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad())
        return Box(nonce = b64(nonce), data = b64(cipher.doFinal(plain)))
    }

    private fun decrypt(box: Box, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, unb64(box.nonce)))
        cipher.updateAAD(aad())
        return cipher.doFinal(unb64(box.data))
    }

    private fun gzip(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

    private fun gunzip(bytes: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun unb64(text: String): ByteArray = Base64.getDecoder().decode(text)

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
