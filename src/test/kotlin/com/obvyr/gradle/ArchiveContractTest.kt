package com.obvyr.gradle

import com.github.luben.zstd.ZstdInputStream
import com.obvyr.gradle.archive.ArchiveBuilder
import com.obvyr.gradle.http.ObvyrApiClient
import com.obvyr.gradle.model.CommandJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

/**
 * Contract tests verifying that the plugin produces requests conforming to the
 * POST /collect endpoint defined in the Obvyr API (api.obvyr.com/openapi.json).
 *
 * Contract requirements:
 *   - Multipart/form-data request with a single field named "archive"
 *   - File named "artifacts.tar.zst" containing a zstd-compressed tar archive
 *   - Archive must contain command.json with required fields:
 *       command (array), user (string), return_code (number),
 *       execution_time_ms (number), executed (ISO string), env (object), tags (array)
 *   - Authorization: Bearer <agentKey> header
 */
class ArchiveContractTest {

    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private val sampleCommandJson = CommandJson(
        command = listOf("gradle", ":test"),
        user = "testuser",
        returnCode = 0,
        executionTimeMs = 1234L,
        executed = "2026-03-16T12:00:00Z",
        env = mapOf("PATH" to "/usr/bin"),
        tags = listOf("ci"),
    )

    // --- HTTP contract ---

    @Test
    fun `collect request uses POST method`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val request = submitArchive()
        assertThat(request.method).isEqualTo("POST")
    }

    @Test
    fun `collect request targets the collect path`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val request = submitArchive()
        assertThat(request.path).isEqualTo("/collect")
    }

    @Test
    fun `collect request includes Authorization Bearer header`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val request = submitArchive(agentKey = "my-agent-key")
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer my-agent-key")
    }

    @Test
    fun `collect request body is multipart form-data`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val request = submitArchive()
        assertThat(request.getHeader("Content-Type") ?: "").startsWith("multipart/form-data")
    }

    @Test
    fun `collect request multipart field is named archive`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val request = submitArchive()
        assertThat(request.body.readUtf8()).contains("name=\"archive\"")
    }

    @Test
    fun `collect request archive part has filename artifacts tar zst`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val request = submitArchive()
        assertThat(request.body.readUtf8()).contains("artifacts.tar.zst")
    }

    // --- Archive format contract ---

    @Test
    fun `archive contains required command_json member`() {
        val entries = listTarEntries(ArchiveBuilder.build(sampleCommandJson))
        assertThat(entries).contains("command.json")
    }

    @Test
    fun `command_json has required command field as array`() {
        val parsed = parseCommandJson(sampleCommandJson)
        assertThat(parsed["command"]?.jsonArray).isNotNull()
    }

    @Test
    fun `command_json has required user field`() {
        val parsed = parseCommandJson(sampleCommandJson)
        assertThat(parsed["user"]).isInstanceOf(JsonPrimitive::class.java)
    }

    @Test
    fun `command_json has required return_code field as number`() {
        val parsed = parseCommandJson(sampleCommandJson)
        val returnCode = parsed["return_code"]
        assertThat(returnCode).isInstanceOf(JsonPrimitive::class.java)
        assertThat((returnCode as JsonPrimitive).isString).isFalse()
    }

    @Test
    fun `command_json has required execution_time_ms field as number`() {
        val parsed = parseCommandJson(sampleCommandJson)
        val execTime = parsed["execution_time_ms"]
        assertThat(execTime).isInstanceOf(JsonPrimitive::class.java)
        assertThat((execTime as JsonPrimitive).isString).isFalse()
    }

    @Test
    fun `command_json has required executed field`() {
        val parsed = parseCommandJson(sampleCommandJson)
        assertThat(parsed["executed"]).isInstanceOf(JsonPrimitive::class.java)
    }

    @Test
    fun `command_json has required env field as object`() {
        val parsed = parseCommandJson(sampleCommandJson)
        assertThat(parsed["env"]).isInstanceOf(JsonObject::class.java)
    }

    @Test
    fun `command_json has required tags field as array`() {
        val parsed = parseCommandJson(sampleCommandJson)
        assertThat(parsed["tags"]?.jsonArray).isNotNull()
    }

    @Test
    fun `command_json contains no unknown top-level fields beyond the seven required`() {
        val parsed = parseCommandJson(sampleCommandJson)
        val requiredFields = setOf("command", "user", "return_code", "execution_time_ms", "executed", "env", "tags")
        assertThat(parsed.keys).containsExactlyInAnyOrderElementsOf(requiredFields)
    }

    // --- Helpers ---

    private fun submitArchive(agentKey: String = "test-key"): RecordedRequest {
        val archiveBytes = ArchiveBuilder.build(sampleCommandJson, "test output", emptyList())
        val client = ObvyrApiClient(agentKey, server.url("/").toString().trimEnd('/'), 10.0, true)
        client.submit(archiveBytes)
        return server.takeRequest()
    }

    private fun listTarEntries(archiveBytes: ByteArray): List<String> {
        val entries = mutableListOf<String>()
        ZstdInputStream(ByteArrayInputStream(archiveBytes)).use { zstd ->
            TarArchiveInputStream(zstd).use { tar ->
                var entry = tar.nextEntry
                while (entry != null) {
                    entries.add(entry.name)
                    entry = tar.nextEntry
                }
            }
        }
        return entries
    }

    private fun parseCommandJson(commandJson: CommandJson): JsonObject {
        val archiveBytes = ArchiveBuilder.build(commandJson)
        ZstdInputStream(ByteArrayInputStream(archiveBytes)).use { zstd ->
            TarArchiveInputStream(zstd).use { tar ->
                var entry = tar.nextEntry
                while (entry != null) {
                    if (entry.name == "command.json") {
                        return Json.parseToJsonElement(tar.readBytes().toString(Charsets.UTF_8)).jsonObject
                    }
                    entry = tar.nextEntry
                }
            }
        }
        throw AssertionError("command.json not found in archive")
    }
}
