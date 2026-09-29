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
