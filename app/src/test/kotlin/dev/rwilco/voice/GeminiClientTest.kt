package dev.rwilco.voice

import dev.rwilco.model.geminiJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Gemini's `generateContent`, answered by a mock server: what is sent, and what each reply comes to. */
class GeminiClientTest {

    private val server = MockWebServer()
    private lateinit var client: GeminiClient
    private val schema = buildJsonObject { put("type", "OBJECT") }

    @BeforeEach
    fun start() {
        server.start()
        client = GeminiClient(key = "AIza-test", baseUrl = server.url("/"), models = listOf("first-model", "second-model"))
    }

    @AfterEach
    fun stop() = server.shutdown()

    private fun answer(reading: String) = MockResponse().setBody(
        buildJsonObject {
            put("candidates", kotlinx.serialization.json.buildJsonArray {
                add(buildJsonObject { put("content", buildJsonObject { put("parts", kotlinx.serialization.json.buildJsonArray { add(buildJsonObject { put("text", reading) }) }) }) })
            })
        }.toString(),
    )

    @Test
    fun `a sentence goes with its instruction and schema, the key in a header, and comes back a reading`() = runBlocking {
        server.enqueue(answer("""{"text":"Comprar pilas","match":"ANY","rules":[{"event":{"kind":"IN_MINUTES","minutes":20}}]}"""))
        val reply = client.read("comprar pilas en veinte minutos", "the instruction", schema)
        assertEquals("Comprar pilas", (reply as GeminiAnswer.Reading).reading.text)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1beta/models/first-model:generateContent", request.path)
        assertEquals("AIza-test", request.getHeader("x-goog-api-key"))
        val body = geminiJson.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("comprar pilas en veinte minutos", body["contents"]!!.jsonArray.first().jsonObject["parts"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("the instruction", body["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("application/json", body["generationConfig"]!!.jsonObject["responseMimeType"]!!.jsonPrimitive.content)
        assertEquals(schema, body["generationConfig"]!!.jsonObject["responseSchema"])
        assertTrue("key" !in request.path!!, "the key never travels in the address")
    }

    @Test
    fun `a model that is not there is passed over for the next`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(answer("""{"text":"Regar","match":"ANY","rules":[]}"""))
        assertTrue(client.read("regar", "i", schema) is GeminiAnswer.Reading)
        assertEquals("/v1beta/models/first-model:generateContent", server.takeRequest().path)
        assertEquals("/v1beta/models/second-model:generateContent", server.takeRequest().path)
    }

    @Test
    fun `a refused key, too many asks, a broken answer and no answer at all`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"status":"INVALID_ARGUMENT","message":"API key not valid. Please pass a valid API key."}}"""))
        assertEquals(GeminiAnswer.KeyRefused, client.read("x", "i", schema))
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"status":"RESOURCE_EXHAUSTED"}}"""))
        assertEquals(GeminiAnswer.Failed, client.read("x", "i", schema))
        server.enqueue(answer("this is not json"))
        assertEquals(GeminiAnswer.Failed, client.read("x", "i", schema))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertEquals(GeminiAnswer.Failed, client.read("x", "i", schema))
        // Not one of the models there is a failure, not the key's fault.
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(GeminiAnswer.Failed, client.read("x", "i", schema))
    }

    @Test
    fun `nothing follows a redirect, so the key goes nowhere else`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://example.com/"))
        assertEquals(GeminiAnswer.Failed, client.read("x", "i", schema))
        assertEquals(1, server.requestCount)
    }
}
