package com.obvyr.gradle

import com.github.luben.zstd.ZstdInputStream
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.assertj.core.api.Assertions.assertThat
import org.gradle.api.Task
import org.gradle.api.logging.Logger
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File

class ObvyrSubmitActionTest {

    private lateinit var server: MockWebServer

    @TempDir
    lateinit var testResultsDir: File

    @TempDir
    lateinit var projectDir: File

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    // --- execute: enabled / agentKey guards ---

    @Test
    fun `execute does nothing when enabled is false`() {
        val logger = mockk<Logger>(relaxed = true)
        val extension = mockExtension(enabledVal = false)

        ObvyrSubmitAction(extension, mockCollector(), testResultsDir, projectDir, logger).execute(mockTask())

        verify(exactly = 0) { logger.warn(any<String>()) }
    }

    @Test
    fun `execute logs warning and returns when agentKey is absent`() {
        val logger = mockk<Logger>(relaxed = true)
        val extension = mockExtension(agentKey = null)

        ObvyrSubmitAction(extension, mockCollector(), testResultsDir, projectDir, logger).execute(mockTask())

        verify { logger.warn(match { it.contains("[Obvyr] No agent key configured") }) }
    }

    @Test
    fun `execute logs warning and returns when agentKey is blank`() {
        val logger = mockk<Logger>(relaxed = true)
        val extension = mockExtension(agentKey = "  ")

        ObvyrSubmitAction(extension, mockCollector(), testResultsDir, projectDir, logger).execute(mockTask())

        verify { logger.warn(match { it.contains("[Obvyr] No agent key configured") }) }
    }

    // --- execute: successful submission ---

    @Test
    fun `execute submits archive to collect endpoint when agentKey is set`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val logger = mockk<Logger>(relaxed = true)
        val extension = mockExtension()

        ObvyrSubmitAction(extension, mockCollector(), testResultsDir, projectDir, logger).execute(mockTask())

        assertThat(server.takeRequest().path).isEqualTo("/collect")
        verify(exactly = 0) { logger.warn(any<String>()) }
    }

    @Test
    fun `execute logs warning when API returns failure`() {
        server.enqueue(MockResponse().setResponseCode(500))
        val logger = mockk<Logger>(relaxed = true)
        val extension = mockExtension()

        ObvyrSubmitAction(extension, mockCollector(), testResultsDir, projectDir, logger).execute(mockTask())

        verify { logger.warn(match { it.contains("[Obvyr] Submission failed") }) }
    }

    @Test
    fun `execute catches exception and logs warning`() {
        val logger = mockk<Logger>(relaxed = true)
        val extension = mockExtension()
        val collector = mockk<ObvyrTestCollector> {
            every { returnCode } throws RuntimeException("unexpected error")
            every { executionTimeMs } returns 100L
            every { output } returns ""
        }

        ObvyrSubmitAction(extension, collector, testResultsDir, projectDir, logger).execute(mockTask())

        verify { logger.warn(match { it.contains("[Obvyr] Error submitting test data") }) }
    }

    // --- execute: output text ---

    @Test
    fun `execute passes non-blank output to archive`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val logger = mockk<Logger>(relaxed = true)
        val extension = mockExtension()

        ObvyrSubmitAction(extension, mockCollector(output = "test output"), testResultsDir, projectDir, logger)
            .execute(mockTask())

        server.takeRequest() // consumed without error
        verify(exactly = 0) { logger.warn(any<String>()) }
    }

    @Test
    fun `execute passes null output when collector output is blank`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val logger = mockk<Logger>(relaxed = true)
        val extension = mockExtension()

        ObvyrSubmitAction(extension, mockCollector(output = ""), testResultsDir, projectDir, logger)
            .execute(mockTask())

        server.takeRequest()
        verify(exactly = 0) { logger.warn(any<String>()) }
    }

    // --- execute: attachment gathering ---

    @Test
    fun `execute includes XML files from testResultsDir as attachments`() {
        server.enqueue(MockResponse().setResponseCode(200))
        File(testResultsDir, "TEST-com.example.MyTest.xml").writeText("<testsuite/>")
        val logger = mockk<Logger>(relaxed = true)
        val extension = mockExtension()

        ObvyrSubmitAction(extension, mockCollector(), testResultsDir, projectDir, logger).execute(mockTask())

        assertThat(extractTarEntries(server.takeRequest())).contains("attachment/TEST-com.example.MyTest.xml")
    }

    @Test
    fun `execute skips XML files exceeding 5MB`() {
        server.enqueue(MockResponse().setResponseCode(200))
        File(testResultsDir, "big.xml").writeBytes(ByteArray(5 * 1024 * 1024 + 1))
        val logger = mockk<Logger>(relaxed = true)
        val extension = mockExtension()

        ObvyrSubmitAction(extension, mockCollector(), testResultsDir, projectDir, logger).execute(mockTask())

        assertThat(extractTarEntries(server.takeRequest())).doesNotContain("attachment/big.xml")
    }

    @Test
    fun `execute skips files that would push total attachments over 10MB`() {
        server.enqueue(MockResponse().setResponseCode(200))
        // Two files each just under 5MB: first fits, second tips over 10MB combined
        val chunk = ByteArray(5 * 1024 * 1024 - 1)
        File(testResultsDir, "a.xml").writeBytes(chunk)
        File(testResultsDir, "b.xml").writeBytes(chunk)
        File(testResultsDir, "c.xml").writeBytes(chunk)
        val logger = mockk<Logger>(relaxed = true)
        val extension = mockExtension()

        ObvyrSubmitAction(extension, mockCollector(), testResultsDir, projectDir, logger).execute(mockTask())

        val body = server.takeRequest().body.readUtf8()
        // At least one was included, at least one was excluded (total exceeds 10MB)
        val included = listOf("a.xml", "b.xml", "c.xml").count { body.contains(it) }
        assertThat(included).isLessThan(3)
    }

    @Test
    fun `execute skips testResultsDir scan when directory does not exist`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val nonExistent = File(projectDir, "build/nonexistent")
        val logger = mockk<Logger>(relaxed = true)
        val extension = mockExtension()

        ObvyrSubmitAction(extension, mockCollector(), nonExistent, projectDir, logger).execute(mockTask())

        assertThat(server.takeRequest().path).isEqualTo("/collect")
    }

    @Test
    fun `execute includes configured attachment paths relative to projectDir`() {
        server.enqueue(MockResponse().setResponseCode(200))
        File(projectDir, "report.txt").writeText("attachment content")
        val logger = mockk<Logger>(relaxed = true)
        val extension = mockExtension(attachmentPaths = listOf("report.txt"))

        ObvyrSubmitAction(extension, mockCollector(), testResultsDir, projectDir, logger).execute(mockTask())

        assertThat(extractTarEntries(server.takeRequest())).contains("attachment/report.txt")
    }

    @Test
    fun `execute skips configured attachment path when file does not exist`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val logger = mockk<Logger>(relaxed = true)
        val extension = mockExtension(attachmentPaths = listOf("missing.txt"))

        ObvyrSubmitAction(extension, mockCollector(), testResultsDir, projectDir, logger).execute(mockTask())

        assertThat(server.takeRequest().path).isEqualTo("/collect")
        verify(exactly = 0) { logger.warn(any<String>()) }
    }

    // --- attachment gathering: edge cases ---

    @Test
    fun `execute ignores non-XML files in testResultsDir`() {
        server.enqueue(MockResponse().setResponseCode(200))
        File(testResultsDir, "output.log").writeText("not xml")
        File(testResultsDir, "TEST-example.xml").writeText("<testsuite/>")
        val logger = mockk<Logger>(relaxed = true)
        val extension = mockExtension()

        ObvyrSubmitAction(extension, mockCollector(), testResultsDir, projectDir, logger).execute(mockTask())

        val entries = extractTarEntries(server.takeRequest())
        assertThat(entries).contains("attachment/TEST-example.xml")
        assertThat(entries).doesNotContain("attachment/output.log")
    }

    @Test
    fun `execute includes attachment path given as absolute path`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val absFile = File(projectDir, "abs.txt")
        absFile.writeText("absolute")
        val logger = mockk<Logger>(relaxed = true)
        val extension = mockExtension(attachmentPaths = listOf(absFile.absolutePath))

        ObvyrSubmitAction(extension, mockCollector(), testResultsDir, projectDir, logger).execute(mockTask())

        assertThat(extractTarEntries(server.takeRequest())).contains("attachment/abs.txt")
    }

    @Test
    fun `execute skips attachment path that resolves to a directory`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val dir = File(projectDir, "subdir").also { it.mkdirs() }
        val logger = mockk<Logger>(relaxed = true)
        val extension = mockExtension(attachmentPaths = listOf(dir.absolutePath))

        ObvyrSubmitAction(extension, mockCollector(), testResultsDir, projectDir, logger).execute(mockTask())

        // Build completes successfully; directory is silently skipped
        assertThat(server.takeRequest().path).isEqualTo("/collect")
        verify(exactly = 0) { logger.warn(any<String>()) }
    }

    @Test
    fun `execute skips attachment paths that would exceed 10MB total`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val chunk = ByteArray(5 * 1024 * 1024 - 1)
        val first = File(projectDir, "first.txt").also { it.writeBytes(chunk) }
        val second = File(projectDir, "second.txt").also { it.writeBytes(chunk) }
        val third = File(projectDir, "third.txt").also { it.writeBytes(chunk) }
        val logger = mockk<Logger>(relaxed = true)
        val extension = mockExtension(
            attachmentPaths = listOf(first.absolutePath, second.absolutePath, third.absolutePath),
        )

        ObvyrSubmitAction(extension, mockCollector(), testResultsDir, projectDir, logger).execute(mockTask())

        val entries = extractTarEntries(server.takeRequest())
        val included = listOf("first.txt", "second.txt", "third.txt")
            .count { entries.contains("attachment/$it") }
        assertThat(included).isLessThan(3)
    }

    @Test
    fun `execute skips configured attachment when file exceeds 5MB`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val bigFile = File(projectDir, "big.xml").also { it.writeBytes(ByteArray(5 * 1024 * 1024 + 1)) }
        val logger = mockk<Logger>(relaxed = true)
        val extension = mockExtension(attachmentPaths = listOf(bigFile.absolutePath))

        ObvyrSubmitAction(extension, mockCollector(), testResultsDir, projectDir, logger).execute(mockTask())

        assertThat(extractTarEntries(server.takeRequest())).doesNotContain("attachment/big.xml")
    }

    @Test
    fun `execute expands glob pattern in attachmentPaths`() {
        server.enqueue(MockResponse().setResponseCode(200))
        File(projectDir, "report-a.xml").writeText("<testsuite/>")
        File(projectDir, "report-b.xml").writeText("<testsuite/>")
        File(projectDir, "other.log").writeText("not matched")
        val logger = mockk<Logger>(relaxed = true)
        val extension = mockExtension(attachmentPaths = listOf("*.xml"))

        ObvyrSubmitAction(extension, mockCollector(), testResultsDir, projectDir, logger).execute(mockTask())

        val entries = extractTarEntries(server.takeRequest())
        assertThat(entries).contains("attachment/report-a.xml", "attachment/report-b.xml")
        assertThat(entries).doesNotContain("attachment/other.log")
    }

    @Test
    fun `execute skips binary files in attachmentPaths`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val binFile = File(projectDir, "native.so").also { it.writeBytes(ByteArray(100)) }
        val logger = mockk<Logger>(relaxed = true)
        val extension = mockExtension(attachmentPaths = listOf(binFile.absolutePath))

        ObvyrSubmitAction(extension, mockCollector(), testResultsDir, projectDir, logger).execute(mockTask())

        assertThat(extractTarEntries(server.takeRequest())).doesNotContain("attachment/native.so")
    }

    @Test
    fun `execute selects higher-priority files when total would exceed limit`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val chunk = ByteArray(4 * 1024 * 1024)
        val logFileA = File(projectDir, "output-a.log").also { it.writeBytes(chunk) }
        val logFileB = File(projectDir, "output-b.log").also { it.writeBytes(chunk) }
        val xmlFile = File(projectDir, "report.xml").also { it.writeBytes(chunk) }
        // Three 4MB files total 12MB > 10MB limit. Without priority sort xml (listed last) is
        // excluded; with priority sort xml (priority 2) wins over both logs (priority 0).
        val logger = mockk<Logger>(relaxed = true)
        val extension = mockExtension(
            attachmentPaths = listOf(logFileA.absolutePath, logFileB.absolutePath, xmlFile.absolutePath),
        )

        ObvyrSubmitAction(extension, mockCollector(), testResultsDir, projectDir, logger).execute(mockTask())

        val entries = extractTarEntries(server.takeRequest())
        assertThat(entries).contains("attachment/report.xml")
        assertThat(entries).doesNotContain("attachment/output-b.log")
    }

    @Test
    fun `execute expands question mark glob pattern in attachmentPaths`() {
        server.enqueue(MockResponse().setResponseCode(200))
        File(projectDir, "report-1.xml").writeText("<testsuite/>")
        File(projectDir, "report-2.xml").writeText("<testsuite/>")
        val logger = mockk<Logger>(relaxed = true)
        val extension = mockExtension(attachmentPaths = listOf("report-?.xml"))

        ObvyrSubmitAction(extension, mockCollector(), testResultsDir, projectDir, logger).execute(mockTask())

        val entries = extractTarEntries(server.takeRequest())
        assertThat(entries).contains("attachment/report-1.xml", "attachment/report-2.xml")
    }

    @Test
    fun `execute handles glob pattern with non-existent base directory`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val logger = mockk<Logger>(relaxed = true)
        val extension = mockExtension(attachmentPaths = listOf("nonexistent/**/*.xml"))

        ObvyrSubmitAction(extension, mockCollector(), testResultsDir, projectDir, logger).execute(mockTask())

        assertThat(server.takeRequest().path).isEqualTo("/collect")
        verify(exactly = 0) { logger.warn(any<String>()) }
    }

    // --- resolve: env var fallback ---

    @Test
    fun `resolveOrNull returns null when env var is blank string`() {
        val extension = mockExtension(agentKey = null)
        val logger = mockk<Logger>(relaxed = true)
        val envLookup: (String) -> String? = { "   " }

        ObvyrSubmitAction(extension, mockCollector(), testResultsDir, projectDir, logger, envLookup).execute(mockTask())

        verify { logger.warn(match { it.contains("[Obvyr] No agent key configured") }) }
    }

    @Test
    fun `execute uses default user when user property is absent and env var is blank`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val extension = mockk<ObvyrExtension> {
            every { enabled } returns boolProp(true)
            every { agentKey } returns strProp("test-key")
            every { apiUrl } returns strProp(server.url("/").toString().trimEnd('/'))
            every { user } returns absentStrProp()
            every { timeout } returns doubleProp(10.0)
            every { verifySsl } returns boolProp(true)
            every { tags } returns listProp(emptyList<String>())
            every { attachmentPaths } returns listProp(emptyList<String>())
            every { envMode } returns absentStrProp()
            every { envInclude } returns absentListProp()
            every { redactPatterns } returns absentListProp()
            every { dryRun } returns absentBoolProp()
        }
        val logger = mockk<Logger>(relaxed = true)

        ObvyrSubmitAction(extension, mockCollector(), testResultsDir, projectDir, logger).execute(mockTask())

        assertThat(server.takeRequest().getHeader("Authorization")).isEqualTo("Bearer test-key")
    }

    @Test
    fun `resolveOrNull returns env var value when property is absent and env var is non-blank`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val extension = mockExtension(agentKey = null)
        val logger = mockk<Logger>(relaxed = true)
        val envLookup: (String) -> String? = { key ->
            if (key == "OBVYR_API_KEY") "env-agent-key" else System.getenv(key)
        }

        ObvyrSubmitAction(extension, mockCollector(), testResultsDir, projectDir, logger, envLookup).execute(mockTask())

        assertThat(server.takeRequest().getHeader("Authorization")).isEqualTo("Bearer env-agent-key")
    }

    // --- resolve: typed property env var fallbacks ---

    @Test
    fun `execute uses OBVYR_TAGS env var when tags property is absent`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val extension = mockk<ObvyrExtension> {
            every { enabled } returns boolProp(true)
            every { agentKey } returns strProp("test-key")
            every { apiUrl } returns strProp(server.url("/").toString().trimEnd('/'))
            every { user } returns strProp("testuser")
            every { timeout } returns doubleProp(10.0)
            every { verifySsl } returns boolProp(true)
            every { tags } returns absentListProp()
            every { attachmentPaths } returns listProp(emptyList<String>())
            every { envMode } returns absentStrProp()
            every { envInclude } returns absentListProp()
            every { redactPatterns } returns absentListProp()
            every { dryRun } returns absentBoolProp()
        }
        val logger = mockk<Logger>(relaxed = true)
        val envLookup: (String) -> String? = { key -> if (key == "OBVYR_TAGS") "ci,unit" else null }

        ObvyrSubmitAction(extension, mockCollector(), testResultsDir, projectDir, logger, envLookup).execute(mockTask())

        val commandJson = extractCommandJsonContent(server.takeRequest())
        assertThat(commandJson).contains("\"ci\"")
        assertThat(commandJson).contains("\"unit\"")
    }

    @Test
    fun `execute uses OBVYR_TIMEOUT env var when timeout property is absent`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val extension = mockk<ObvyrExtension> {
            every { enabled } returns boolProp(true)
            every { agentKey } returns strProp("test-key")
            every { apiUrl } returns strProp(server.url("/").toString().trimEnd('/'))
            every { user } returns strProp("testuser")
            every { timeout } returns absentDoubleProp()
            every { verifySsl } returns boolProp(true)
            every { tags } returns listProp(emptyList<String>())
            every { attachmentPaths } returns listProp(emptyList<String>())
            every { envMode } returns absentStrProp()
            every { envInclude } returns absentListProp()
            every { redactPatterns } returns absentListProp()
            every { dryRun } returns absentBoolProp()
        }
        val logger = mockk<Logger>(relaxed = true)
        val envLookup: (String) -> String? = { key -> if (key == "OBVYR_TIMEOUT") "30.0" else null }

        ObvyrSubmitAction(extension, mockCollector(), testResultsDir, projectDir, logger, envLookup).execute(mockTask())

        assertThat(server.takeRequest().path).isEqualTo("/collect")
    }

    @Test
    fun `execute uses default timeout when OBVYR_TIMEOUT env var is not a valid number`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val extension = mockk<ObvyrExtension> {
            every { enabled } returns boolProp(true)
            every { agentKey } returns strProp("test-key")
            every { apiUrl } returns strProp(server.url("/").toString().trimEnd('/'))
            every { user } returns strProp("testuser")
            every { timeout } returns absentDoubleProp()
            every { verifySsl } returns boolProp(true)
            every { tags } returns listProp(emptyList<String>())
            every { attachmentPaths } returns listProp(emptyList<String>())
            every { envMode } returns absentStrProp()
            every { envInclude } returns absentListProp()
            every { redactPatterns } returns absentListProp()
            every { dryRun } returns absentBoolProp()
        }
        val logger = mockk<Logger>(relaxed = true)
        val envLookup: (String) -> String? = { key -> if (key == "OBVYR_TIMEOUT") "not-a-number" else null }

        ObvyrSubmitAction(extension, mockCollector(), testResultsDir, projectDir, logger, envLookup).execute(mockTask())

        assertThat(server.takeRequest().path).isEqualTo("/collect")
    }

    @Test
    fun `execute uses OBVYR_VERIFY_SSL env var when verifySsl property is absent`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val extension = mockk<ObvyrExtension> {
            every { enabled } returns boolProp(true)
            every { agentKey } returns strProp("test-key")
            every { apiUrl } returns strProp(server.url("/").toString().trimEnd('/'))
            every { user } returns strProp("testuser")
            every { timeout } returns doubleProp(10.0)
            every { verifySsl } returns absentBoolProp()
            every { tags } returns listProp(emptyList<String>())
            every { attachmentPaths } returns listProp(emptyList<String>())
            every { envMode } returns absentStrProp()
            every { envInclude } returns absentListProp()
            every { redactPatterns } returns absentListProp()
            every { dryRun } returns absentBoolProp()
        }
        val logger = mockk<Logger>(relaxed = true)
        val envLookup: (String) -> String? = { key -> if (key == "OBVYR_VERIFY_SSL") "true" else null }

        ObvyrSubmitAction(extension, mockCollector(), testResultsDir, projectDir, logger, envLookup).execute(mockTask())

        assertThat(server.takeRequest().path).isEqualTo("/collect")
    }

    @Test
    fun `execute uses OBVYR_ATTACHMENT_PATHS env var when attachmentPaths property is absent`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val reportFile = File(projectDir, "report.xml").also { it.writeText("<testsuite/>") }
        val extension = mockk<ObvyrExtension> {
            every { enabled } returns boolProp(true)
            every { agentKey } returns strProp("test-key")
            every { apiUrl } returns strProp(server.url("/").toString().trimEnd('/'))
            every { user } returns strProp("testuser")
            every { timeout } returns doubleProp(10.0)
            every { verifySsl } returns boolProp(true)
            every { tags } returns listProp(emptyList<String>())
            every { attachmentPaths } returns absentListProp()
            every { envMode } returns absentStrProp()
            every { envInclude } returns absentListProp()
            every { redactPatterns } returns absentListProp()
            every { dryRun } returns absentBoolProp()
        }
        val logger = mockk<Logger>(relaxed = true)
        val envLookup: (String) -> String? = { key ->
            if (key == "OBVYR_ATTACHMENT_PATHS") reportFile.absolutePath else null
        }

        ObvyrSubmitAction(extension, mockCollector(), testResultsDir, projectDir, logger, envLookup).execute(mockTask())

        assertThat(extractTarEntries(server.takeRequest())).contains("attachment/report.xml")
    }

    @Test
    fun `execute uses defaults when typed properties are absent and env vars are unset`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val extension = mockk<ObvyrExtension> {
            every { enabled } returns boolProp(true)
            every { agentKey } returns strProp("test-key")
            every { apiUrl } returns strProp(server.url("/").toString().trimEnd('/'))
            every { user } returns strProp("testuser")
            every { timeout } returns absentDoubleProp()
            every { verifySsl } returns absentBoolProp()
            every { tags } returns absentListProp()
            every { attachmentPaths } returns absentListProp()
            every { envMode } returns absentStrProp()
            every { envInclude } returns absentListProp()
            every { redactPatterns } returns absentListProp()
            every { dryRun } returns absentBoolProp()
        }
        val logger = mockk<Logger>(relaxed = true)
        val envLookup: (String) -> String? = { null }

        ObvyrSubmitAction(extension, mockCollector(), testResultsDir, projectDir, logger, envLookup).execute(mockTask())

        assertThat(server.takeRequest().path).isEqualTo("/collect")
        verify(exactly = 0) { logger.warn(any<String>()) }
    }

    // --- resolve: config resolution order ---

    @Test
    fun `execute uses DSL apiUrl over default`() {
        val customServer = MockWebServer()
        customServer.start()
        customServer.enqueue(MockResponse().setResponseCode(200))
        val logger = mockk<Logger>(relaxed = true)
        val extension = mockExtension(apiUrl = customServer.url("/").toString().trimEnd('/'))

        ObvyrSubmitAction(extension, mockCollector(), testResultsDir, projectDir, logger).execute(mockTask())

        assertThat(customServer.takeRequest().path).isEqualTo("/collect")
        customServer.shutdown()
    }

    // --- environment capture ---

    private val sampleEnvironment = mapOf(
        "CI" to "true",
        "GITHUB_RUN_ID" to "42",
        "OBVYR_API_KEY" to "agt_super_secret",
        "AWS_SECRET_ACCESS_KEY" to "wJalrXUtnFEMI",
        "HOME" to "/home/runner",
        "BUILD_FLAVOUR" to "release",
    )

    private fun submitAndReadCommandJson(
        extension: ObvyrExtension,
        collector: ObvyrTestCollector = mockCollector(),
        envLookup: (String) -> String? = { null },
        logger: Logger = mockk(relaxed = true),
    ): String {
        server.enqueue(MockResponse().setResponseCode(200))
        ObvyrSubmitAction(extension, collector, testResultsDir, projectDir, logger, envLookup, sampleEnvironment)
            .execute(mockTask())
        return extractCommandJsonContent(server.takeRequest())
    }

    @Test
    fun `execute sends only allow-listed environment variables by default`() {
        val commandJson = submitAndReadCommandJson(mockExtension())

        assertThat(commandJson).contains("\"CI\":\"true\"")
        assertThat(commandJson).contains("\"GITHUB_RUN_ID\":\"42\"")
        assertThat(commandJson).doesNotContain("HOME")
        assertThat(commandJson).doesNotContain("BUILD_FLAVOUR")
        assertThat(commandJson).doesNotContain("AWS_SECRET_ACCESS_KEY")
    }

    @Test
    fun `execute never sends OBVYR_ variables including the API key`() {
        val commandJson = submitAndReadCommandJson(mockExtension())

        assertThat(commandJson).doesNotContain("OBVYR_API_KEY")
        assertThat(commandJson).doesNotContain("agt_super_secret")
    }

    @Test
    fun `execute never sends OBVYR_ variables even when named in envInclude`() {
        val extension = mockExtension(envInclude = listOf("OBVYR_API_KEY"))

        val commandJson = submitAndReadCommandJson(extension)

        assertThat(commandJson).doesNotContain("OBVYR_API_KEY")
        assertThat(commandJson).doesNotContain("agt_super_secret")
    }

    @Test
    fun `execute sends extra variables named in envInclude`() {
        val extension = mockExtension(envInclude = listOf("BUILD_FLAVOUR"))

        val commandJson = submitAndReadCommandJson(extension)

        assertThat(commandJson).contains("\"BUILD_FLAVOUR\":\"release\"")
        assertThat(commandJson).doesNotContain("HOME")
    }

    @Test
    fun `execute redacts the value of an included variable whose name looks secret`() {
        val extension = mockExtension(envInclude = listOf("AWS_SECRET_ACCESS_KEY"))

        val commandJson = submitAndReadCommandJson(extension)

        assertThat(commandJson).contains("\"AWS_SECRET_ACCESS_KEY\":\"[REDACTED]\"")
        assertThat(commandJson).doesNotContain("wJalrXUtnFEMI")
    }

    @Test
    fun `execute uses OBVYR_ENV_INCLUDE env var when envInclude property is absent`() {
        val envLookup: (String) -> String? = { key -> if (key == "OBVYR_ENV_INCLUDE") "BUILD_FLAVOUR, HOME" else null }

        val commandJson = submitAndReadCommandJson(mockExtension(), envLookup = envLookup)

        assertThat(commandJson).contains("\"BUILD_FLAVOUR\":\"release\"")
        assertThat(commandJson).contains("\"HOME\":\"/home/runner\"")
    }

    @Test
    fun `execute sends no environment when envMode is none`() {
        val commandJson = submitAndReadCommandJson(mockExtension(envMode = "none"))

        assertThat(commandJson).contains("\"env\":{}")
    }

    @Test
    fun `execute sends no environment when envMode is none even if envInclude is set`() {
        val extension = mockExtension(envMode = "none", envInclude = listOf("BUILD_FLAVOUR"))

        val commandJson = submitAndReadCommandJson(extension)

        assertThat(commandJson).contains("\"env\":{}")
    }

    @Test
    fun `execute uses OBVYR_ENV_MODE env var when envMode property is absent`() {
        val envLookup: (String) -> String? = { key -> if (key == "OBVYR_ENV_MODE") "NONE" else null }

        val commandJson = submitAndReadCommandJson(mockExtension(), envLookup = envLookup)

        assertThat(commandJson).contains("\"env\":{}")
    }

    @Test
    fun `execute sends no environment and warns when envMode is unknown`() {
        val logger = mockk<Logger>(relaxed = true)

        val commandJson = submitAndReadCommandJson(mockExtension(envMode = "everything"), logger = logger)

        assertThat(commandJson).contains("\"env\":{}")
        verify { logger.warn(match { it.contains("Unknown envMode 'everything'") }) }
    }

    // --- redaction ---

    @Test
    fun `execute redacts secrets in output before upload`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val collector = mockCollector(output = "connecting with password=hunter2 and Bearer abc.def.ghi")

        ObvyrSubmitAction(mockExtension(), collector, testResultsDir, projectDir, mockk(relaxed = true), { null })
            .execute(mockTask())

        val output = extractTarEntryContent(server.takeRequest(), "output.txt")
        assertThat(output).isEqualTo("connecting with password=[REDACTED] and [REDACTED]")
    }

    @Test
    fun `execute redacts output with configured redactPatterns`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val extension = mockExtension(redactPatterns = listOf("ACME-\\d+"))

        ObvyrSubmitAction(extension, mockCollector(output = "ticket ACME-1234 failed"), testResultsDir, projectDir, mockk(relaxed = true), { null })
            .execute(mockTask())

        assertThat(extractTarEntryContent(server.takeRequest(), "output.txt")).isEqualTo("ticket [REDACTED] failed")
    }

    @Test
    fun `execute uses OBVYR_REDACT_PATTERNS env var when redactPatterns property is absent`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val envLookup: (String) -> String? = { key -> if (key == "OBVYR_REDACT_PATTERNS") "ACME-\\d+" else null }

        ObvyrSubmitAction(mockExtension(), mockCollector(output = "ticket ACME-1234 failed"), testResultsDir, projectDir, mockk(relaxed = true), envLookup)
            .execute(mockTask())

        assertThat(extractTarEntryContent(server.takeRequest(), "output.txt")).isEqualTo("ticket [REDACTED] failed")
    }

    @Test
    fun `execute does not submit and warns when a redact pattern is not a valid regex`() {
        val logger = mockk<Logger>(relaxed = true)
        val extension = mockExtension(redactPatterns = listOf("("))

        ObvyrSubmitAction(extension, mockCollector(output = "anything"), testResultsDir, projectDir, logger, { null })
            .execute(mockTask())

        assertThat(server.requestCount).isEqualTo(0)
        verify { logger.warn(match { it.contains("[Obvyr] Error submitting test data") }) }
    }

    // --- dry run ---

    private fun captureLifecycle(logger: Logger): List<String> {
        val messages = mutableListOf<String>()
        every { logger.lifecycle(capture(messages)) } returns Unit
        return messages
    }

    @Test
    fun `execute with dryRun makes no network call and prints the payload`() {
        val logger = mockk<Logger>(relaxed = true)
        val messages = captureLifecycle(logger)
        val extension = mockExtension(dryRun = true, envInclude = listOf("BUILD_FLAVOUR"))
        val collector = mockCollector(output = "token=abc123 done")

        ObvyrSubmitAction(extension, collector, testResultsDir, projectDir, logger, { null }, sampleEnvironment)
            .execute(mockTask())

        assertThat(server.requestCount).isEqualTo(0)
        val printed = messages.joinToString("\n")
        assertThat(printed).contains("--- command.json ---")
        assertThat(printed).contains("\"BUILD_FLAVOUR\": \"release\"")
        assertThat(printed).contains("--- output.txt ---\ntoken=[REDACTED] done")
        assertThat(printed).doesNotContain("agt_super_secret")
        assertThat(printed).doesNotContain("abc123")
        verify(exactly = 0) { logger.warn(any<String>()) }
    }

    @Test
    fun `execute with dryRun does not require an agent key`() {
        val logger = mockk<Logger>(relaxed = true)
        val messages = captureLifecycle(logger)
        val extension = mockExtension(agentKey = null, dryRun = true)

        ObvyrSubmitAction(extension, mockCollector(), testResultsDir, projectDir, logger, { null }, sampleEnvironment)
            .execute(mockTask())

        assertThat(messages.joinToString("\n")).contains("--- command.json ---")
        verify(exactly = 0) { logger.warn(any<String>()) }
    }

    @Test
    fun `execute uses OBVYR_DRY_RUN env var when dryRun property is absent`() {
        val logger = mockk<Logger>(relaxed = true)
        val messages = captureLifecycle(logger)
        val envLookup: (String) -> String? = { key -> if (key == "OBVYR_DRY_RUN") "true" else null }

        ObvyrSubmitAction(mockExtension(), mockCollector(), testResultsDir, projectDir, logger, envLookup, sampleEnvironment)
            .execute(mockTask())

        assertThat(server.requestCount).isEqualTo(0)
        assertThat(messages.joinToString("\n")).contains("--- command.json ---")
    }

    @Test
    fun `execute with dryRun false still submits`() {
        server.enqueue(MockResponse().setResponseCode(200))

        ObvyrSubmitAction(mockExtension(dryRun = false), mockCollector(), testResultsDir, projectDir, mockk(relaxed = true), { null })
            .execute(mockTask())

        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `execute with dryRun lists attachments by name and size without their content`() {
        File(testResultsDir, "TEST-sample.xml").writeText("<testsuite name=\"secret-content\"/>")
        val logger = mockk<Logger>(relaxed = true)
        val messages = captureLifecycle(logger)

        ObvyrSubmitAction(mockExtension(dryRun = true), mockCollector(), testResultsDir, projectDir, logger, { null })
            .execute(mockTask())

        val printed = messages.joinToString("\n")
        assertThat(printed).contains("--- attachment/TEST-sample.xml (")
        assertThat(printed).contains("content not shown")
        assertThat(printed).doesNotContain("secret-content")
    }

    // --- Helpers ---

    private fun mockExtension(
        enabledVal: Boolean = true,
        agentKey: String? = "test-key",
        apiUrl: String? = null,
        userVal: String = "testuser",
        timeout: Double = 10.0,
        verifySsl: Boolean = true,
        tags: List<String> = emptyList(),
        attachmentPaths: List<String> = emptyList(),
        envMode: String? = null,
        envInclude: List<String>? = null,
        redactPatterns: List<String>? = null,
        dryRun: Boolean? = null,
    ): ObvyrExtension = mockk {
        every { this@mockk.enabled } returns boolProp(enabledVal)
        every { this@mockk.agentKey } returns if (agentKey != null) strProp(agentKey) else absentStrProp()
        every { this@mockk.apiUrl } returns strProp(apiUrl ?: server.url("/").toString().trimEnd('/'))
        every { this@mockk.user } returns strProp(userVal)
        every { this@mockk.timeout } returns doubleProp(timeout)
        every { this@mockk.verifySsl } returns boolProp(verifySsl)
        every { this@mockk.tags } returns listProp(tags)
        every { this@mockk.attachmentPaths } returns listProp(attachmentPaths)
        every { this@mockk.envMode } returns if (envMode != null) strProp(envMode) else absentStrProp()
        every { this@mockk.envInclude } returns if (envInclude != null) listProp(envInclude) else absentListProp()
        every { this@mockk.redactPatterns } returns
            if (redactPatterns != null) listProp(redactPatterns) else absentListProp()
        every { this@mockk.dryRun } returns if (dryRun != null) boolProp(dryRun) else absentBoolProp()
    }

    private fun mockCollector(
        returnCode: Int = 0,
        executionTimeMs: Long = 100L,
        output: String = "",
    ): ObvyrTestCollector = mockk {
        every { this@mockk.returnCode } returns returnCode
        every { this@mockk.executionTimeMs } returns executionTimeMs
        every { this@mockk.output } returns output
    }

    private fun mockTask(path: String = ":test"): Task = mockk {
        every { this@mockk.path } returns path
    }

    private fun strProp(value: String): Property<String> = mockk {
        every { isPresent } returns true
        every { get() } returns value
        every { getOrElse(any()) } returns value
    }

    private fun absentStrProp(): Property<String> = mockk {
        every { isPresent } returns false
        every { getOrElse(any()) } answers { firstArg() }
    }

    private fun boolProp(value: Boolean): Property<Boolean> = mockk {
        every { isPresent } returns true
        every { get() } returns value
        every { getOrElse(any()) } returns value
    }

    private fun doubleProp(value: Double): Property<Double> = mockk {
        every { isPresent } returns true
        every { get() } returns value
        every { getOrElse(any()) } returns value
    }

    private fun <T : Any> listProp(values: List<T>): ListProperty<T> = mockk {
        every { isPresent } returns true
        every { get() } returns values
        every { getOrElse(any()) } returns values
    }

    private fun absentBoolProp(): Property<Boolean> = mockk {
        every { isPresent } returns false
        every { getOrElse(any()) } answers { firstArg() }
    }

    private fun absentDoubleProp(): Property<Double> = mockk {
        every { isPresent } returns false
        every { getOrElse(any()) } answers { firstArg() }
    }

    private fun absentListProp(): ListProperty<String> = mockk {
        every { isPresent } returns false
        every { getOrElse(any()) } returns emptyList()
    }

    /**
     * Extracts the raw archive bytes from a multipart request body by locating the
     * part data between the MIME header separator and the closing boundary.
     */
    private fun extractArchiveBytes(request: RecordedRequest): ByteArray {
        val rawBytes = request.body.readByteArray()
        val CRLF2 = byteArrayOf(0x0D, 0x0A, 0x0D, 0x0A)
        var dataStart = -1
        for (i in 0 until rawBytes.size - 3) {
            if (rawBytes[i] == CRLF2[0] && rawBytes[i + 1] == CRLF2[1] &&
                rawBytes[i + 2] == CRLF2[2] && rawBytes[i + 3] == CRLF2[3]
            ) {
                dataStart = i + 4
                break
            }
        }
        check(dataStart >= 0) { "No multipart header separator found" }
        val CRLF_DASHES = byteArrayOf(0x0D, 0x0A, 0x2D, 0x2D)
        var dataEnd = rawBytes.size
        for (i in dataStart until rawBytes.size - 3) {
            if (rawBytes[i] == CRLF_DASHES[0] && rawBytes[i + 1] == CRLF_DASHES[1] &&
                rawBytes[i + 2] == CRLF_DASHES[2] && rawBytes[i + 3] == CRLF_DASHES[3]
            ) {
                dataEnd = i
                break
            }
        }
        return rawBytes.copyOfRange(dataStart, dataEnd)
    }

    /** Returns the list of tar entry names from the zstd-compressed archive in the request. */
    private fun extractTarEntries(request: RecordedRequest): List<String> =
        ZstdInputStream(ByteArrayInputStream(extractArchiveBytes(request))).use { zstd ->
            TarArchiveInputStream(zstd).use { tar ->
                generateSequence { tar.nextEntry }.map { it.name }.toList()
            }
        }

    /** Reads and returns the content of command.json from the archive in the request. */
    private fun extractCommandJsonContent(request: RecordedRequest): String =
        extractTarEntryContent(request, "command.json")

    /** Reads and returns the content of the named entry from the archive in the request. */
    private fun extractTarEntryContent(request: RecordedRequest, name: String): String {
        ZstdInputStream(ByteArrayInputStream(extractArchiveBytes(request))).use { zstd ->
            TarArchiveInputStream(zstd).use { tar ->
                var entry = tar.nextEntry
                while (entry != null) {
                    if (entry.name == name) return tar.bufferedReader().readText()
                    entry = tar.nextEntry
                }
            }
        }
        throw AssertionError("$name not found in archive")
    }
}
