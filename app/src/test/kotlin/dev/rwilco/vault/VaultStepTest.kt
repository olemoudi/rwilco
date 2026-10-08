package dev.rwilco.vault

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VaultStepTest {

    @Test
    fun `off means nothing, whatever the content`() {
        assertEquals(VaultStep.DISABLED, nextVaultStep(enabled = false, fingerprint = "a", lastUploaded = null))
    }

    @Test
    fun `unchanged content costs no call`() {
        assertEquals(VaultStep.NOTHING_CHANGED, nextVaultStep(enabled = true, fingerprint = "a", lastUploaded = "a"))
    }

    @Test
    fun `changed content, or none uploaded yet, uploads`() {
        assertEquals(VaultStep.UPLOAD, nextVaultStep(enabled = true, fingerprint = "b", lastUploaded = "a"))
        assertEquals(VaultStep.UPLOAD, nextVaultStep(enabled = true, fingerprint = "a", lastUploaded = null))
    }

    @Test
    fun `something that went to nothing is a collapse, and less of it is not`() {
        assertTrue(wentEmpty(was = 312, now = 0))
        assertFalse(wentEmpty(was = 312, now = 1), "a deletion is not a collapse")
        assertFalse(wentEmpty(was = 0, now = 0))
        assertFalse(wentEmpty(was = null, now = 0), "a copy that never carried this part says nothing about it")
    }

    @Test
    fun `settings that went back to the factory are a collapse, and settings never anybody's are not`() {
        assertTrue(settingsWentBare(hadOwn = true, bareNow = true, lostSinceCopy = true), "places and presets, lost, then none: a reset")
        assertFalse(settingsWentBare(hadOwn = true, bareNow = true, lostSinceCopy = false), "the last place deleted by hand")
        assertFalse(settingsWentBare(hadOwn = true, bareNow = false, lostSinceCopy = true), "lost in part, the rest still there")
        assertFalse(settingsWentBare(hadOwn = false, bareNow = true, lostSinceCopy = true), "a person who never kept anything has lost nothing")
        assertFalse(settingsWentBare(hadOwn = null, bareNow = true, lostSinceCopy = true), "a copy from before this was written down says nothing")
    }

    private val home = VaultState(
        enabled = true, owner = "ole", repo = "vault", pat = "old-token",
        remoteSha = "sha-there", lastUploadedFingerprint = "print", lastAttemptSha = "attempt",
        lastUploadedRows = 40, lastUploadedSettingsLength = 900, lastOutcome = VaultOutcome.AUTH,
    )

    @Test
    fun `the copy this phone last wrote keeps every cursor, wherever it now is`() {
        val token = home.movedTo("Ole", "VAULT", "new-token", remoteSha = "sha-there")
        assertEquals(home.copy(owner = "Ole", repo = "VAULT", pat = "new-token", lastOutcome = null), token)
        val renamed = home.movedTo("ole", "vault-renamed", "old-token", remoteSha = "sha-there")
        assertEquals("print", renamed.lastUploadedFingerprint, "a repository renamed still holds this phone's copy")
    }

    @Test
    fun `the same repository made again, empty, gets everything`() {
        val remade = home.movedTo("ole", "vault", "new-token", remoteSha = null)
        assertNull(remade.remoteSha)
        assertNull(remade.lastUploadedFingerprint, "judged by the name, it read as up to date while it stayed empty")
    }

    @Test
    fun `somebody else's copy in the same repository stays the conflict it was`() {
        val other = home.movedTo("ole", "vault", "new-token", remoteSha = "sha-other")
        assertEquals("sha-there", other.remoteSha)
        assertEquals("print", other.lastUploadedFingerprint)
    }

    @Test
    fun `another repository starts from what it holds and gets everything`() {
        val empty = home.movedTo("ole", "vault-2", "new-token", remoteSha = null)
        assertEquals("vault-2", empty.repo)
        assertNull(empty.remoteSha, "an empty repository is written to without a sha")
        assertNull(empty.lastUploadedFingerprint, "nothing of this phone is there yet: the next run uploads")
        assertNull(empty.lastAttemptSha)
        assertNull(empty.lastOutcome)
        assertEquals(40, empty.lastUploadedRows, "the guard is about this phone, not the repository")
        assertEquals(900, empty.lastUploadedSettingsLength)

        val holding = home.movedTo("someone", "vault", "new-token", remoteSha = "sha-new")
        assertEquals("sha-new", holding.remoteSha, "the upload replaces what is there, having been asked")
        assertNull(holding.lastUploadedFingerprint)
    }

    @Test
    fun `a new passphrase changes the key and nothing else, and owes the copy again`() {
        val key = ByteArray(32) { 7 }
        val salt = ByteArray(16) { 9 }
        val rekeyed = home.rekeyed(key, salt, iterations = 1_000)
        assertEquals(VaultState.encode(key), rekeyed.key)
        assertEquals(VaultState.encode(salt), rekeyed.salt)
        assertEquals(1_000, rekeyed.iterations)
        assertNull(rekeyed.lastUploadedFingerprint, "the copy up there is under the old key until an upload replaces it")
        assertEquals(home.copy(key = rekeyed.key, salt = rekeyed.salt, iterations = 1_000, lastUploadedFingerprint = null), rekeyed)
    }

    @Test
    fun `replacing a copy that is there needs something to put in its place`() {
        val strong = "correct horse 42 battery"
        val old = "twelve chars"
        assertFalse(mayReplaceExisting(localRows = 0, opened = true, passphrase = strong), "a new phone before its restore")
        assertFalse(mayReplaceExisting(localRows = 0, opened = false, passphrase = strong), "a new phone with a mistyped passphrase")
        assertTrue(mayReplaceExisting(localRows = 3, opened = true, passphrase = strong))
        assertTrue(mayReplaceExisting(localRows = 3, opened = false, passphrase = strong), "a new vault under this passphrase")
        assertTrue(mayReplaceExisting(localRows = 3, opened = true, passphrase = old), "the copy's own passphrase, from before the rule")
        assertFalse(mayReplaceExisting(localRows = 3, opened = false, passphrase = old), "a new vault must meet the rule")
    }

    @Test
    fun `a conflict whose remote is our own last attempt is ours`() {
        assertEquals(ConflictVerdict.OURS_LANDED, judgeConflict(remoteSha = "abc", lastAttemptSha = "abc"))
        assertEquals(ConflictVerdict.OTHER_WRITER, judgeConflict(remoteSha = "abc", lastAttemptSha = "def"))
        assertEquals(ConflictVerdict.OTHER_WRITER, judgeConflict(remoteSha = "abc", lastAttemptSha = null))
        assertEquals(ConflictVerdict.OTHER_WRITER, judgeConflict(remoteSha = null, lastAttemptSha = null))
    }

    @Test
    fun `github statuses mean what they mean`() {
        assertNull(classifyGitHubStatus(200, null, null))
        assertNull(classifyGitHubStatus(201, null, null))
        assertEquals(TransportFailure.REPO_MISSING, classifyGitHubStatus(301, null, null))
        assertEquals(TransportFailure.AUTH, classifyGitHubStatus(401, null, null))
        assertEquals(TransportFailure.AUTH, classifyGitHubStatus(403, "4999", null))
        assertEquals(TransportFailure.AUTH, classifyGitHubStatus(403, null, null))
        assertEquals(TransportFailure.TRANSIENT, classifyGitHubStatus(403, "0", null))
        assertEquals(TransportFailure.TRANSIENT, classifyGitHubStatus(403, null, "60"))
        assertEquals(TransportFailure.REPO_MISSING, classifyGitHubStatus(404, null, null))
        assertEquals(TransportFailure.CONFLICT, classifyGitHubStatus(409, null, null))
        assertEquals(TransportFailure.CONFLICT, classifyGitHubStatus(422, null, null))
        assertEquals(TransportFailure.TRANSIENT, classifyGitHubStatus(429, null, null))
        assertEquals(TransportFailure.TRANSIENT, classifyGitHubStatus(502, null, null))
    }

    @Test
    fun `a repository name is a path segment and nothing else`() {
        assertTrue(isRepoName("olemoudi"))
        assertTrue(isRepoName("rwilco-vault.backup_1"))
        assertFalse(isRepoName(""))
        assertFalse(isRepoName(".."))
        assertFalse(isRepoName("a/b"))
        assertFalse(isRepoName("a b"))
        assertFalse(isRepoName("ñu"))
    }

    @Test
    fun `a conflict whose remote is the previous run's attempt is ours to write over`() {
        assertEquals(ConflictVerdict.EARLIER_LANDED, judgeConflict(remoteSha = "old", lastAttemptSha = "new", earlierAttemptSha = "old"))
        assertEquals(ConflictVerdict.OURS_LANDED, judgeConflict(remoteSha = "new", lastAttemptSha = "new", earlierAttemptSha = "old"))
        assertEquals(ConflictVerdict.OTHER_WRITER, judgeConflict(remoteSha = "theirs", lastAttemptSha = "new", earlierAttemptSha = "old"))
        assertEquals(ConflictVerdict.OTHER_WRITER, judgeConflict(remoteSha = null, lastAttemptSha = "new", earlierAttemptSha = "old"))
    }
}
