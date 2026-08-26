package dev.rwilco.vault

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class VaultCryptoTest {

    private val salt = ByteArray(16) { it.toByte() }
    private val passphrase = "correct horse battery staple"
    private val key = VaultCrypto.deriveKey(passphrase, salt, iterations = TEST_ITERATIONS)
    private val plain = "hello, vault".toByteArray()

    @Test
    fun `pbkdf2 matches the RFC 7914 vectors`() {
        assertEquals(
            "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc" +
                "49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783",
            VaultCrypto.pbkdf2HmacSha256("passwd".toByteArray(), "salt".toByteArray(), 1, 64).toHex(),
        )
        assertEquals(
            "4ddcd8f60b98be21830cee5ef22701f9641a4418d04c0414aeff08876b34ab56" +
                "a1d425a1225833549adb841b51c9b3176a272bdebba1d078478f62b397f33c8d",
            VaultCrypto.pbkdf2HmacSha256("Password".toByteArray(), "NaCl".toByteArray(), 80_000, 64).toHex(),
        )
    }

    @Test
    fun `the derivation agrees with the JDK's, eñe included`() {
        val spec = PBEKeySpec("contraseña muy larga".toCharArray(), salt, TEST_ITERATIONS, 256)
        val theirs = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        assertArrayEquals(theirs, VaultCrypto.deriveKey("contraseña muy larga", salt, TEST_ITERATIONS))
    }

    @Test
    fun `a decomposed eñe derives the same key as a composed one`() {
        val composed = "contraseña muy larga"
        val decomposed = "contrase\u006e\u0303a muy larga"
        assertArrayEquals(
            VaultCrypto.deriveKey(composed, salt, TEST_ITERATIONS),
            VaultCrypto.deriveKey(decomposed, salt, TEST_ITERATIONS),
        )
    }

    @Test
    fun `a vault opens with its passphrase and holds the bytes`() {
        val envelope = VaultCrypto.seal(plain, key, salt, TEST_ITERATIONS)
        assertArrayEquals(plain, VaultCrypto.open(envelope, key))
    }

    @Test
    fun `the header reads without a key`() {
        val header = VaultCrypto.header(VaultCrypto.seal(plain, key, salt, TEST_ITERATIONS))
        assertEquals(VAULT_FORMAT, header.format)
        assertArrayEquals(salt, header.salt)
        assertEquals(TEST_ITERATIONS, header.iterations)
    }

    @Test
    fun `the wrong passphrase is named as such`() {
        val envelope = VaultCrypto.seal(plain, key, salt, TEST_ITERATIONS)
        val other = VaultCrypto.deriveKey("incorrect horse battery staple", salt, TEST_ITERATIONS)
        assertThrows(VaultException.WrongPassphrase::class.java) { VaultCrypto.open(envelope, other) }
    }

    @Test
    fun `a damaged payload is corruption, not a wrong passphrase`() {
        val text = String(VaultCrypto.seal(plain, key, salt, TEST_ITERATIONS))
        val data = text.indexOf("\"data\":\"", text.indexOf("\"payload\"")) + "\"data\":\"".length + 4
        val damaged = text.substring(0, data) + (if (text[data] == 'A') 'B' else 'A') + text.substring(data + 1)
        assertThrows(VaultException.Corrupt::class.java) { VaultCrypto.open(damaged.toByteArray(), key) }
    }

    @Test
    fun `a container from the future is refused rather than guessed at`() {
        val text = String(VaultCrypto.seal(plain, key, salt, TEST_ITERATIONS)).replace("\"format\":1", "\"format\":2")
        assertThrows(VaultException.NewerThanThisApp::class.java) { VaultCrypto.open(text.toByteArray(), key) }
        assertThrows(VaultException.NewerThanThisApp::class.java) { VaultCrypto.header(text.toByteArray()) }
    }

    @Test
    fun `garbage is corruption`() {
        assertThrows(VaultException.Corrupt::class.java) { VaultCrypto.open("<html>".toByteArray(), key) }
        assertThrows(VaultException.Corrupt::class.java) { VaultCrypto.header("{}".toByteArray()) }
    }

    @Test
    fun `two seals of the same bytes differ`() {
        val first = VaultCrypto.seal(plain, key, salt, TEST_ITERATIONS)
        val second = VaultCrypto.seal(plain, key, salt, TEST_ITERATIONS)
        assertFalse(first.contentEquals(second))
    }

    @Test
    fun `the blob sha is git's`() {
        assertEquals("e69de29bb2d1d6434b8b29ae775ad8c2e48c5391", VaultCrypto.gitBlobSha(ByteArray(0)))
        assertEquals("d670460b4b4aece5915caf5c68d12f560a9fe3e4", VaultCrypto.gitBlobSha("test content\n".toByteArray()))
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    companion object {
        /** Enough to exercise the loop; the real count is a second of a phone's time per test. */
        const val TEST_ITERATIONS = 1_000
    }
}
