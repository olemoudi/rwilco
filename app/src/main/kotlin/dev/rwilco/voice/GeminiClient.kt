package dev.rwilco.voice

import dev.rwilco.model.GeminiReading
import dev.rwilco.model.geminiJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import kotlin.coroutines.resumeWithException
import java.io.IOException
import java.util.concurrent.TimeUnit

/** What asking Gemini came to. */
sealed interface GeminiAnswer {
    data class Reading(val reading: GeminiReading) : GeminiAnswer

    /** The key was refused (or none of the models answer to it): Settings is where that is fixed. */
    data object KeyRefused : GeminiAnswer

    /** No answer: offline, too slow, too many asks, or an answer that would not read. */
    data object Failed : GeminiAnswer
}

/**
 * Gemini's `generateContent`, asked once per sentence the grammar could not read (0.178.0). Plain
 * OkHttp and the app's own JSON, no SDK: one request, held to [schema] (`responseSchema`), and
 * what goes in it is the sentence's text, the moment and the person's own words for hours and
 * places — never audio, never a coordinate (`geminiInstruction`). The key travels in a header,
 * and nothing here follows a redirect, so it cannot travel anywhere else.
 *
 * Models are tried in order and the next is asked only when one is not there (404): a "latest"
 * alias first, so a model being retired does not take the feature with it, then named ones.
 */
class GeminiClient(
    private val key: String,
    private val baseUrl: HttpUrl = DEFAULT_BASE_URL,
    private val models: List<String> = MODELS,
) {
    private val client get() = SHARED

    suspend fun read(sentence: String, instruction: String, schema: JsonObject): GeminiAnswer = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("systemInstruction") { putJsonArray("parts") { add(buildJsonObject { put("text", instruction) }) } }
            putJsonArray("contents") {
                add(
                    buildJsonObject {
                        put("role", "user")
                        putJsonArray("parts") { add(buildJsonObject { put("text", sentence) }) }
                    },
                )
            }
            putJsonObject("generationConfig") {
                put("responseMimeType", "application/json")
                put("responseSchema", schema)
                put("temperature", 0)
            }
        }.toString().toRequestBody(JSON)
        for (model in models) {
            val url = baseUrl.newBuilder().addPathSegments("v1beta/models").addPathSegment("$model:generateContent").build()
            // A key that cannot be a header value is a key Gemini would refuse anyway — and the
            // error OkHttp throws for it carries the key in its message, so it is said as that.
            val request = runCatching { Request.Builder().url(url).header("x-goog-api-key", geminiKeyOf(key)).post(body).build() }
                .getOrElse { return@withContext GeminiAnswer.KeyRefused }
            val answer = try {
                // Enqueued and awaited rather than executed, so a sheet let go of cancels the
                // request itself and not only the wait for it.
                client.newCall(request).await().use { response ->
                    when (response.code) {
                        in 200..299 -> readingOf(response.body.string())?.let { GeminiAnswer.Reading(it) } ?: GeminiAnswer.Failed
                        404 -> null
                        400, 401, 403 -> if (keyRefused(response.body.string())) GeminiAnswer.KeyRefused else GeminiAnswer.Failed
                        else -> GeminiAnswer.Failed
                    }
                }
            } catch (_: IOException) {
                GeminiAnswer.Failed
            }
            if (answer != null) return@withContext answer
        }
        // Not one of them there: nothing the key can fix, and nothing to say but that it failed.
        GeminiAnswer.Failed
    }

    /** The answer's text, which the schema makes a JSON reading; null when it is anything else. */
    private fun readingOf(body: String): GeminiReading? = runCatching {
        val text = geminiJson.parseToJsonElement(body).jsonObject["candidates"]!!.jsonArray.first()
            .jsonObject["content"]!!.jsonObject["parts"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content
        geminiJson.decodeFromString(GeminiReading.serializer(), text)
    }.getOrNull()

    /** A 400 is the key only when it says so; any other 400 is a request this build got wrong. */
    private fun keyRefused(body: String): Boolean = body.contains("API_KEY", ignoreCase = true) || body.contains("API key", ignoreCase = true) ||
        body.contains("PERMISSION_DENIED") || body.contains("UNAUTHENTICATED")

    companion object {
        val DEFAULT_BASE_URL: HttpUrl = "https://generativelanguage.googleapis.com/".toHttpUrl()

        /** One client for every ask: its connections stay warm, and the key goes per request. */
        private val SHARED: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(CONNECT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(CALL_SECONDS, TimeUnit.SECONDS)
                .build()
        }

        /** Flash-Lite: the free tier's fastest, and plenty for one sentence. */
        val MODELS = listOf("gemini-flash-lite-latest", "gemini-3.5-flash-lite", "gemini-flash-latest")

        private const val CONNECT_SECONDS = 3L
        private const val CALL_SECONDS = 10L
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/** The call, awaited without blocking a thread, and cancelled with the coroutine that waits for it. */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(
        object : Callback {
            override fun onResponse(call: Call, response: Response) = continuation.resume(response) { response.close() }
            override fun onFailure(call: Call, e: IOException) = continuation.resumeWithException(e)
        },
    )
    continuation.invokeOnCancellation { cancel() }
}

/** Where dictation asks Gemini; a device test puts a fake answer here, as [VoiceInput] holds a fake ear. */
object GeminiInput {
    var ask: suspend (key: String, sentence: String, instruction: String, schema: JsonObject) -> GeminiAnswer =
        { key, sentence, instruction, schema -> GeminiClient(key).read(sentence, instruction, schema) }
}

