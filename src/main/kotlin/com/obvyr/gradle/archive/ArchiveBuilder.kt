package com.obvyr.gradle.archive

import com.github.luben.zstd.ZstdOutputStream
import com.obvyr.gradle.model.CommandJson
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import java.io.ByteArrayOutputStream

object ArchiveBuilder {
    private val json = Json { encodeDefaults = true }
    private val prettyJson = Json { encodeDefaults = true; prettyPrint = true }

    fun build(
        commandJson: CommandJson,
        outputText: String? = null,
        attachments: List<Pair<String, ByteArray>> = emptyList(),
    ): ByteArray {
        val tarBytes = buildTar(commandJson, outputText, attachments)
        return compressWithZstd(tarBytes)
    }

    /**
     * Renders what [build] would upload as text, for a dry run. command.json is
     * pretty-printed and output.txt is shown in full. Attachments are listed with
     * their sizes only.
     */
    fun preview(
        commandJson: CommandJson,
        outputText: String? = null,
        attachments: List<Pair<String, ByteArray>> = emptyList(),
    ): String {
        val sections = mutableListOf("--- command.json ---\n${prettyJson.encodeToString(commandJson)}")
        if (outputText != null) {
            sections.add("--- output.txt ---\n$outputText")
        }
        for ((name, data) in attachments) {
            sections.add("--- attachment/$name (${data.size} bytes, content not shown) ---")
        }
        return sections.joinToString("\n\n")
    }

    private fun buildTar(
        commandJson: CommandJson,
        outputText: String?,
        attachments: List<Pair<String, ByteArray>>,
    ): ByteArray {
        val baos = ByteArrayOutputStream()
        TarArchiveOutputStream(baos).use { tar ->
            addEntry(tar, "command.json", json.encodeToString(commandJson).toByteArray(Charsets.UTF_8))
            if (outputText != null) {
                addEntry(tar, "output.txt", outputText.toByteArray(Charsets.UTF_8))
            }
            for ((name, data) in attachments) {
                addEntry(tar, "attachment/$name", data)
            }
            tar.finish()
        }
        return baos.toByteArray()
    }

    private fun compressWithZstd(data: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream()
        ZstdOutputStream(baos).use { it.write(data) }
        return baos.toByteArray()
    }

    private fun addEntry(tar: TarArchiveOutputStream, name: String, data: ByteArray) {
        val entry = TarArchiveEntry(name)
        entry.size = data.size.toLong()
        tar.putArchiveEntry(entry)
        tar.write(data)
        tar.closeArchiveEntry()
    }
}
