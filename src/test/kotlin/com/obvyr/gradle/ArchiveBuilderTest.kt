package com.obvyr.gradle

import com.github.luben.zstd.ZstdInputStream
import com.obvyr.gradle.archive.ArchiveBuilder
import com.obvyr.gradle.model.CommandJson
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

class ArchiveBuilderTest {

    private val sampleCommandJson = CommandJson(
        command = listOf("gradle", ":test"),
        user = "testuser",
        returnCode = 42,
        executionTimeMs = 1234L,
        executed = "2026-03-16T12:00:00Z",
        env = mapOf("PATH" to "/usr/bin"),
        tags = listOf("test"),
    )

    @Test
    fun `build returns non-empty ByteArray`() {
        val result = ArchiveBuilder.build(sampleCommandJson)
        assertThat(result).isNotEmpty()
    }

    @Test
    fun `archive contains command_json entry`() {
        val result = ArchiveBuilder.build(sampleCommandJson)
        val entries = listTarEntries(result)
        assertThat(entries).contains("command.json")
    }

    @Test
    fun `command_json contains snake_case field names`() {
        val result = ArchiveBuilder.build(sampleCommandJson)
        val content = readTarEntry(result, "command.json")
        assertThat(content).contains("return_code")
        assertThat(content).contains("execution_time_ms")
        assertThat(content).doesNotContain("returnCode")
        assertThat(content).doesNotContain("executionTimeMs")
    }

    @Test
    fun `command_json contains correct numeric values`() {
        val result = ArchiveBuilder.build(sampleCommandJson)
        val content = readTarEntry(result, "command.json")
        assertThat(content).contains("\"return_code\":42")
        assertThat(content).contains("\"execution_time_ms\":1234")
    }

    @Test
    fun `archive contains output_txt when outputText is provided`() {
        val result = ArchiveBuilder.build(sampleCommandJson, outputText = "test output")
        val entries = listTarEntries(result)
        assertThat(entries).contains("output.txt")
    }

    @Test
    fun `archive does not contain output_txt when outputText is null`() {
        val result = ArchiveBuilder.build(sampleCommandJson, outputText = null)
        val entries = listTarEntries(result)
        assertThat(entries).doesNotContain("output.txt")
    }

    @Test
    fun `archive contains attachment entries`() {
        val attachments = listOf(
            Pair("result.xml", "<xml/>".toByteArray()),
            Pair("report.txt", "report".toByteArray()),
        )
        val result = ArchiveBuilder.build(sampleCommandJson, attachments = attachments)
        val entries = listTarEntries(result)
        assertThat(entries).contains("attachment/result.xml")
        assertThat(entries).contains("attachment/report.txt")
    }

    @Test
    fun `output_txt content matches provided text`() {
        val outputText = "test build output"
        val result = ArchiveBuilder.build(sampleCommandJson, outputText = outputText)
        val content = readTarEntry(result, "output.txt")
        assertThat(content).isEqualTo(outputText)
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

    private fun readTarEntry(archiveBytes: ByteArray, entryName: String): String {
        ZstdInputStream(ByteArrayInputStream(archiveBytes)).use { zstd ->
            TarArchiveInputStream(zstd).use { tar ->
                var entry = tar.nextEntry
                while (entry != null) {
                    if (entry.name == entryName) {
                        return tar.readBytes().toString(Charsets.UTF_8)
                    }
                    entry = tar.nextEntry
                }
            }
        }
        throw IllegalArgumentException("Entry '$entryName' not found in archive")
    }
}
