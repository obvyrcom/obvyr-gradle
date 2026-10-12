package com.obvyr.gradle

import com.obvyr.gradle.redaction.Redaction
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RedactionTest {

    // --- allow-list ---

    @Test
    fun `allow-list is exactly the documented set of CI identifiers`() {
        assertThat(Redaction.ENV_ALLOWLIST).containsExactlyInAnyOrder(
            "CI", "CONTINUOUS_INTEGRATION",
            "GITHUB_ACTIONS", "GITHUB_RUN_ID", "GITHUB_RUN_NUMBER", "GITHUB_WORKFLOW",
            "GITHUB_REPOSITORY", "GITHUB_SHA", "GITHUB_REF_NAME",
            "GITLAB_CI", "CI_PIPELINE_ID", "CI_JOB_ID", "CI_COMMIT_SHA", "CI_COMMIT_REF_NAME",
            "JENKINS_URL", "BUILD_NUMBER",
            "BUILDKITE", "BUILDKITE_BUILD_NUMBER",
            "CIRCLECI", "CIRCLE_BUILD_NUM", "CIRCLE_SHA1",
        )
    }

    // --- redactText ---

    @Test
    fun `redactText redacts AWS access key ids`() {
        assertThat(Redaction.redactText("key AKIAIOSFODNN7EXAMPLE and ASIAIOSFODNN7EXAMPLE end"))
            .isEqualTo("key [REDACTED] and [REDACTED] end")
    }

    @Test
    fun `redactText redacts bearer tokens case-insensitively`() {
        assertThat(Redaction.redactText("Authorization: bearer abc.DEF-123_x~+/="))
            .isEqualTo("Authorization: [REDACTED]")
    }

    @Test
    fun `redactText redacts assignments but keeps the name visible`() {
        assertThat(Redaction.redactText("password=hunter2 secret: shh API_KEY=abc"))
            .isEqualTo("password=[REDACTED] secret: [REDACTED] API_KEY=[REDACTED]")
    }

    @Test
    fun `redactText leaves harmless text unchanged`() {
        assertThat(Redaction.redactText("BUILD SUCCESSFUL in 4s")).isEqualTo("BUILD SUCCESSFUL in 4s")
    }

    @Test
    fun `redactText applies extra patterns`() {
        assertThat(Redaction.redactText("ticket ACME-1234 and ACME-9", listOf("ACME-\\d+")))
            .isEqualTo("ticket [REDACTED] and [REDACTED]")
    }

    // --- redactCommand ---

    @Test
    fun `redactCommand redacts secrets inside an argument`() {
        assertThat(Redaction.redactCommand(listOf("gradle", "-Ptoken=abc", "test")))
            .containsExactly("gradle", "-Ptoken=[REDACTED]", "test")
    }

    @Test
    fun `redactCommand redacts the value following a secret flag`() {
        assertThat(Redaction.redactCommand(listOf("tool", "--api-key", "abc123", "--verbose")))
            .containsExactly("tool", "--api-key", "[REDACTED]", "--verbose")
    }

    @Test
    fun `redactCommand does not treat a flag with an inline value as a secret flag`() {
        assertThat(Redaction.redactCommand(listOf("tool", "--name=key", "next")))
            .containsExactly("tool", "--name=key", "next")
    }

    @Test
    fun `redactCommand does not treat a non-flag argument as a secret flag`() {
        assertThat(Redaction.redactCommand(listOf("tool", "monkey", "next")))
            .containsExactly("tool", "monkey", "next")
    }

    @Test
    fun `redactCommand applies extra patterns`() {
        assertThat(Redaction.redactCommand(listOf("gradle", "ACME-1"), listOf("ACME-\\d+")))
            .containsExactly("gradle", "[REDACTED]")
    }

    // --- filterEnvironment ---

    private val environment = mapOf(
        "CI" to "true",
        "GITHUB_SHA" to "deadbeef",
        "CI_JOB_TOKEN" to "glcbt-secret",
        "HOME" to "/home/me",
        "OBVYR_API_KEY" to "agt_secret",
        "MY_TOKEN" to "tok",
        "NOTE" to "password=hunter2",
    )

    @Test
    fun `filterEnvironment sends only the allow-list by default`() {
        assertThat(Redaction.filterEnvironment(environment, Redaction.ENV_MODE_ALLOWLIST))
            .containsOnly(entry("CI", "true"), entry("GITHUB_SHA", "deadbeef"))
    }

    @Test
    fun `filterEnvironment does not match allow-list by prefix`() {
        assertThat(Redaction.filterEnvironment(environment, Redaction.ENV_MODE_ALLOWLIST))
            .doesNotContainKey("CI_JOB_TOKEN")
    }

    @Test
    fun `filterEnvironment returns nothing when mode is none`() {
        assertThat(Redaction.filterEnvironment(environment, Redaction.ENV_MODE_NONE, listOf("HOME"))).isEmpty()
    }

    @Test
    fun `filterEnvironment adds included names`() {
        assertThat(Redaction.filterEnvironment(environment, Redaction.ENV_MODE_ALLOWLIST, listOf("HOME")))
            .containsEntry("HOME", "/home/me")
    }

    @Test
    fun `filterEnvironment never sends OBVYR_ variables even when included`() {
        assertThat(Redaction.filterEnvironment(environment, Redaction.ENV_MODE_ALLOWLIST, listOf("OBVYR_API_KEY")))
            .doesNotContainKey("OBVYR_API_KEY")
    }

    @Test
    fun `filterEnvironment redacts the value when the name looks secret`() {
        assertThat(Redaction.filterEnvironment(environment, Redaction.ENV_MODE_ALLOWLIST, listOf("MY_TOKEN")))
            .containsEntry("MY_TOKEN", "[REDACTED]")
    }

    @Test
    fun `filterEnvironment redacts secrets inside included values`() {
        assertThat(Redaction.filterEnvironment(environment, Redaction.ENV_MODE_ALLOWLIST, listOf("NOTE")))
            .containsEntry("NOTE", "password=[REDACTED]")
    }

    @Test
    fun `filterEnvironment applies extra patterns to values`() {
        assertThat(
            Redaction.filterEnvironment(
                mapOf("GITHUB_SHA" to "build-777"),
                Redaction.ENV_MODE_ALLOWLIST,
                extraPatterns = listOf("build-\\d+"),
            ),
        ).containsEntry("GITHUB_SHA", "[REDACTED]")
    }

    private fun entry(key: String, value: String) = java.util.AbstractMap.SimpleEntry(key, value)
}
