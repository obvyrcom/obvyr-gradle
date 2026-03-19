package com.obvyr.gradle

import com.obvyr.gradle.archive.ArchiveBuilder
import com.obvyr.gradle.http.ObvyrApiClient
import com.obvyr.gradle.model.CommandJson
import org.gradle.api.Task
import org.gradle.api.logging.Logger
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import java.io.File
import java.nio.file.FileSystems
import java.time.Instant

private const val MAX_FILE_BYTES = 5 * 1024 * 1024L
private const val MAX_TOTAL_BYTES = 10 * 1024 * 1024L

private val TEXT_EXTENSIONS = setOf("xml", "json", "yaml", "yml", "csv", "txt", "html", "htm", "log")

private val ATTACHMENT_PRIORITY = mapOf(
    "xml" to 2, "json" to 2,
    "yaml" to 1, "yml" to 1, "csv" to 1,
    "txt" to 0, "html" to 0, "htm" to 0, "log" to 0,
)

class ObvyrSubmitAction(
    private val extension: ObvyrExtension,
    private val collector: ObvyrTestCollector,
    private val testResultsDir: File,
    private val projectDir: File,
    private val logger: Logger,
    @JvmField internal val envLookup: (String) -> String? = System::getenv,
) {
    fun execute(task: Task) {
        if (!extension.enabled.getOrElse(true)) return

        val agentKey = resolve(extension.agentKey, "OBVYR_API_KEY", null)
        if (agentKey.isNullOrBlank()) {
            logger.warn("[Obvyr] No agent key configured. Set obvyr { agentKey = \"...\" } or OBVYR_API_KEY env var.")
            return
        }

        try {
            val user = resolve(extension.user, "OBVYR_USER", System.getProperty("user.name", "unknown"))
            val apiUrl = resolve(extension.apiUrl, "OBVYR_API_URL", "https://api.obvyr.com")
            val timeout = resolveDouble(extension.timeout, "OBVYR_TIMEOUT", 10.0)
            val verifySsl = resolveBoolean(extension.verifySsl, "OBVYR_VERIFY_SSL", true)
            val tags = resolveList(extension.tags, "OBVYR_TAGS")

            val commandJson = CommandJson(
                command = listOf("gradle", task.path),
                user = user,
                returnCode = collector.returnCode,
                executionTimeMs = collector.executionTimeMs,
                executed = Instant.now().toString(),
                env = System.getenv(),
                tags = tags,
            )

            val outputText = collector.output.ifBlank { null }
            val attachments = gatherAttachments(resolveList(extension.attachmentPaths, "OBVYR_ATTACHMENT_PATHS"))

            val archiveData = ArchiveBuilder.build(commandJson, outputText, attachments)
            val client = ObvyrApiClient(agentKey, apiUrl, timeout, verifySsl)
            val success = client.submit(archiveData)
            if (!success) {
                logger.warn("[Obvyr] Submission failed. Check agent key and API URL.")
            }
        } catch (e: Exception) {
            logger.warn("[Obvyr] Error submitting test data: ${e.message}")
        }
    }

    private fun gatherAttachments(attachmentPaths: List<String>): List<Pair<String, ByteArray>> {
        val result = mutableListOf<Pair<String, ByteArray>>()
        var totalSize = 0L

        // Gather JUnit XML results
        if (testResultsDir.exists()) {
            testResultsDir.walkTopDown()
                .filter { it.isFile && it.extension == "xml" }
                .sortedByDescending { it.lastModified() }
                .forEach { file ->
                    val size = file.length()
                    if (size <= MAX_FILE_BYTES && totalSize + size <= MAX_TOTAL_BYTES) {
                        result.add(Pair(file.name, file.readBytes()))
                        totalSize += size
                    }
                }
        }

        // Gather configured attachment paths (glob-expanded, text-only, priority-ordered)
        val candidates = attachmentPaths
            .flatMap { resolvePattern(it) }
            .filter { it.isFile && it.extension.lowercase() in TEXT_EXTENSIONS }
            .sortedByDescending { ATTACHMENT_PRIORITY.getOrDefault(it.extension.lowercase(), 0) }
        for (file in candidates) {
            val size = file.length()
            if (size <= MAX_FILE_BYTES && totalSize + size <= MAX_TOTAL_BYTES) {
                result.add(Pair(file.name, file.readBytes()))
                totalSize += size
            }
        }

        return result
    }

    private fun resolvePattern(pattern: String): List<File> {
        val absPattern = if (File(pattern).isAbsolute) pattern else "$projectDir/$pattern"

        if (!absPattern.contains('*') && !absPattern.contains('?')) {
            return listOf(File(absPattern))
        }

        val parts = absPattern.replace('\\', '/').split('/')
        val firstGlobIndex = parts.indexOfFirst { it.contains('*') || it.contains('?') }
        val walkRoot = File(parts.take(firstGlobIndex).joinToString("/"))

        if (!walkRoot.isDirectory) return emptyList()

        val matcher = FileSystems.getDefault().getPathMatcher("glob:$absPattern")
        return walkRoot.walkTopDown()
            .filter { it.isFile && matcher.matches(it.toPath()) }
            .toList()
    }

    private fun resolveDouble(property: Property<Double>, envVar: String, default: Double): Double {
        if (property.isPresent) return property.get()
        val envValue = envLookup(envVar)
        if (!envValue.isNullOrBlank()) return envValue.toDoubleOrNull() ?: default
        return default
    }

    private fun resolveBoolean(property: Property<Boolean>, envVar: String, default: Boolean): Boolean {
        if (property.isPresent) return property.get()
        val envValue = envLookup(envVar)
        if (!envValue.isNullOrBlank()) return envValue.toBoolean()
        return default
    }

    private fun resolveList(property: ListProperty<String>, envVar: String): List<String> {
        if (property.isPresent) return property.get()
        val envValue = envLookup(envVar)
        if (!envValue.isNullOrBlank()) return envValue.split(",").map { it.trim() }.filter { it.isNotBlank() }
        return emptyList()
    }

    @JvmName("resolveNonNull")
    private fun resolve(property: Property<String>, envVar: String, default: String): String =
        resolveOrNull(property, envVar) ?: default

    @JvmName("resolveNullable")
    private fun resolve(property: Property<String>, envVar: String, default: String?): String? =
        resolveOrNull(property, envVar) ?: default

    private fun resolveOrNull(property: Property<String>, envVar: String): String? {
        if (property.isPresent) return property.get()
        val envValue = envLookup(envVar)
        if (!envValue.isNullOrBlank()) return envValue
        return null
    }
}
