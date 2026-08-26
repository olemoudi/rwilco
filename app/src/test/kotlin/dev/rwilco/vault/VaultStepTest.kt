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
}
