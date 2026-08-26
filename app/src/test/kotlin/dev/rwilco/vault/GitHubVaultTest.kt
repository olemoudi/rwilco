package dev.rwilco.vault

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Base64

/** GitHub's Contents API, answered by a mock server: what is sent, and what each reply means. */
class GitHubVaultTest {

    private val server = MockWebServer()
    private lateinit var vault: GitHubVault
    private val bytes = "{\"rwilco\":\"vault\"}".toByteArray()
    private val sha = VaultCrypto.gitBlobSha(bytes)

    @BeforeEach
    fun start() {
        server.start()
        vault = GitHubVault(owner = "ole", repo = "rwilco-vault", pat = "github_pat_x", userAgent = "rwilco/33", baseUrl = server.url("/"))
    }

    @AfterEach
    fun stop() = server.shutdown()

    @Test
    fun `read brings the bytes and the sha, and asks the way GitHub wants`() = runBlocking {
        // GitHub wraps the base64 at sixty columns.
        val wrapped = Base64.getMimeEncoder(60, "\n".toByteArray()).encodeToString(bytes)
        server.enqueue(MockResponse().setBody("""{"sha":"$sha","size":${bytes.size},"content":"${wrapped.replace("\n", "\\n")}","encoding":"base64"}"""))

        val remote = vault.read()!!

        assertArrayEquals(bytes, remote.bytes)
        assertEquals(sha, remote.sha)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/repos/ole/rwilco-vault/contents/rwilco.vault", request.path)
        assertEquals("Bearer github_pat_x", request.getHeader("Authorization"))
        assertEquals("application/vnd.github.object+json", request.getHeader("Accept"))
        assertEquals("2022-11-28", request.getHeader("X-GitHub-Api-Version"))
        assertEquals("rwilco/33", request.getHeader("User-Agent"))
    }

    @Test
    fun `no file yet reads as null`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"message":"Not Found"}"""))
        assertNull(vault.read())
    }

    @Test
    fun `a file too big for the object form is fetched raw`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"sha":"$sha","size":${bytes.size},"content":"","encoding":"none"}"""))
        server.enqueue(MockResponse().setBody(String(bytes)))

        val remote = vault.read()!!

        assertArrayEquals(bytes, remote.bytes)
        assertEquals(sha, remote.sha)
        server.takeRequest()
        assertEquals("application/vnd.github.raw+json", server.takeRequest().getHeader("Accept"))
    }

    @Test
    fun `write sends the bytes as base64 with the sha it replaces, and returns the new one`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"content":{"sha":"$sha"},"commit":{"sha":"c0ffee"}}"""))

        assertEquals(sha, vault.write(bytes, replacingSha = "0ld"))

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/repos/ole/rwilco-vault/contents/rwilco.vault", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"content\":\"${Base64.getEncoder().encodeToString(bytes)}\""), body)
        assertTrue(body.contains("\"sha\":\"0ld\""), body)
        assertTrue(body.contains("\"message\":\"rwilco vault\""), body)
    }

    @Test
    fun `a first write carries no sha`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"content":{"sha":"$sha"}}"""))
        vault.write(bytes, replacingSha = null)
        assertFalse(server.takeRequest().body.readUtf8().contains("\"sha\""))
    }

    @Test
    fun `each refusal means what it means`() = runBlocking {
        assertFailure(TransportFailure.CONFLICT, MockResponse().setResponseCode(409).setBody("""{"message":"is at 1 but expected 2"}"""))
        assertFailure(TransportFailure.CONFLICT, MockResponse().setResponseCode(422).setBody("""{"message":"\"sha\" wasn't supplied."}"""))
        assertFailure(TransportFailure.AUTH, MockResponse().setResponseCode(401).setBody("""{"message":"Bad credentials"}"""))
        assertFailure(TransportFailure.AUTH, MockResponse().setResponseCode(403).setBody("""{"message":"Resource not accessible by personal access token"}"""))
        assertFailure(TransportFailure.TRANSIENT, MockResponse().setResponseCode(403).addHeader("x-ratelimit-remaining", "0").setBody("""{"message":"API rate limit exceeded"}"""))
        assertFailure(TransportFailure.REPO_MISSING, MockResponse().setResponseCode(404).setBody("""{"message":"Not Found"}"""))
        assertFailure(TransportFailure.TRANSIENT, MockResponse().setResponseCode(502).setBody("bad gateway"))
        assertFailure(TransportFailure.TRANSIENT, MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
    }

    @Test
    fun `a redirect is not followed`() = runBlocking {
        assertFailure(TransportFailure.REPO_MISSING, MockResponse().setResponseCode(301).addHeader("Location", server.url("/elsewhere").toString()))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `the probe says whether the repository is private and writable`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"private":true,"permissions":{"admin":true,"push":true,"pull":true}}"""))
        val info = vault.probe()
        assertTrue(info.isPrivate)
        assertTrue(info.canPush)
        assertEquals("/repos/ole/rwilco-vault", server.takeRequest().path)

        server.enqueue(MockResponse().setBody("""{"private":false,"permissions":{"push":false,"pull":true}}"""))
        val open = vault.probe()
        assertFalse(open.isPrivate)
        assertFalse(open.canPush)
    }

    private suspend fun assertFailure(expected: TransportFailure, response: MockResponse) {
        server.enqueue(response)
        val failure = runCatching { vault.write(bytes, null) }.exceptionOrNull()
        assertTrue(failure is VaultTransportException, "expected a transport failure, got $failure")
        assertEquals(expected, (failure as VaultTransportException).failure)
    }
}
