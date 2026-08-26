package dev.rwilco.vault

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * The vault as a file in a private GitHub repository, through the Contents API: one GET to see
 * what is there, one PUT to replace it, each PUT a commit — so the repository's history is the
 * vault's history, for free. A fine-grained token scoped to that one repository with
 * `contents: read/write` is all the access it needs.
 *
 * Nothing here follows a redirect: the token goes in a header, and a header must not travel
 * to wherever a redirect points. Paths are built segment by segment, so a name somebody typed
 * cannot climb out of the repository.
 */
class GitHubVault(
    private val owner: String,
    private val repo: String,
    private val pat: String,
    private val userAgent: String,
    private val baseUrl: HttpUrl = DEFAULT_BASE_URL,
) : VaultTransport {

    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun read(): RemoteVault? = withContext(Dispatchers.IO) {
        val listing = call(get(contentsUrl(), ACCEPT_OBJECT)) { resp ->
            if (resp.code == 404) return@call null
            failIfRefused(resp)
            json.decodeFromString(Contents.serializer(), resp.body.string())
        } ?: return@withContext null
        // Above a megabyte the object form comes back with no content in it; the raw form has
        // no sha. So: the sha from the first, the bytes from the second when the first is empty.
        val bytes = if (listing.content.isNullOrEmpty() && listing.size > 0) {
            call(get(contentsUrl(), ACCEPT_RAW)) { resp -> failIfRefused(resp); resp.body.bytes() }
        } else {
            Base64.getMimeDecoder().decode(listing.content.orEmpty())
        }
        RemoteVault(bytes, listing.sha)
    }

    override suspend fun write(bytes: ByteArray, replacingSha: String?): String = withContext(Dispatchers.IO) {
        val body = json.encodeToString(Put.serializer(), Put(content = Base64.getEncoder().encodeToString(bytes), sha = replacingSha))
        val request = request(contentsUrl(), ACCEPT_JSON).put(body.toRequestBody(JSON_MEDIA_TYPE)).build()
        call(request) { resp ->
            failIfRefused(resp)
            json.decodeFromString(PutReply.serializer(), resp.body.string()).content.sha
        }
    }

    override suspend fun probe(): RepoInfo = withContext(Dispatchers.IO) {
        call(get(repoUrl(), ACCEPT_JSON)) { resp ->
            failIfRefused(resp)
            val repo = json.decodeFromString(Repo.serializer(), resp.body.string())
            RepoInfo(isPrivate = repo.isPrivate, canPush = repo.permissions?.push == true)
        }
    }

    private fun repoUrl(): HttpUrl = baseUrl.newBuilder()
        .addPathSegment("repos").addPathSegment(owner).addPathSegment(repo)
        .build()

    private fun contentsUrl(): HttpUrl = repoUrl().newBuilder()
        .addPathSegment("contents").addPathSegment(VAULT_FILE)
        .build()

    private fun get(url: HttpUrl, accept: String): Request = request(url, accept).get().build()

    private fun request(url: HttpUrl, accept: String): Request.Builder = Request.Builder()
        .url(url)
        .header("Authorization", "Bearer $pat")
        .header("Accept", accept)
        .header("X-GitHub-Api-Version", API_VERSION)
        .header("User-Agent", userAgent)

    private inline fun <T> call(request: Request, read: (Response) -> T): T {
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw VaultTransportException(TransportFailure.TRANSIENT, "network: ${e.message}", e)
        }
        return response.use { resp ->
            try {
                read(resp)
            } catch (e: VaultTransportException) {
                throw e
            } catch (e: Exception) {
                // A body that is not what the API promised: not worth an immediate retry, but
                // not a reason to give up on the vault either.
                throw VaultTransportException(TransportFailure.TRANSIENT, "unexpected reply: ${e.message}", e)
            }
        }
    }

    private fun failIfRefused(resp: Response) {
        val failure = classifyGitHubStatus(resp.code, resp.header("x-ratelimit-remaining"), resp.header("retry-after")) ?: return
        throw VaultTransportException(failure, "github ${resp.code}")
    }

    @Serializable
    private data class Contents(val sha: String, val size: Long = 0, val content: String? = null, val encoding: String? = null)

    @Serializable
    private data class Put(val message: String = COMMIT_MESSAGE, val content: String, val sha: String? = null)

    @Serializable
    private data class PutReply(val content: Contents)

    @Serializable
    private data class Repo(@SerialName("private") val isPrivate: Boolean = false, val permissions: Permissions? = null)

    @Serializable
    private data class Permissions(val push: Boolean = false)

    companion object {
        val DEFAULT_BASE_URL: HttpUrl = "https://api.github.com/".toHttpUrl()
        private const val API_VERSION = "2022-11-28"
        private const val ACCEPT_JSON = "application/vnd.github+json"
        private const val ACCEPT_OBJECT = "application/vnd.github.object+json"
        private const val ACCEPT_RAW = "application/vnd.github.raw+json"
        /** Says nothing, on purpose: the commit log is the one thing the remote can read. */
        private const val COMMIT_MESSAGE = "rwilco vault"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
    }
}
