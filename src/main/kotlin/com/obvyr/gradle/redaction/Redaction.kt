package com.obvyr.gradle.redaction

/**
 * Controls what the plugin sends: environment filtering and secret redaction.
 *
 * Mirrors the behaviour of `obvyr_cli` so both clients capture the same data.
 * All functions are pure: callers pass in the environment and patterns explicitly.
 */
object Redaction {
    const val REDACTED = "[REDACTED]"

    const val ENV_MODE_ALLOWLIST = "allowlist"
    const val ENV_MODE_NONE = "none"

    /** The plugin's own settings (including its API key) are never sent. */
    private const val ALWAYS_EXCLUDED_PREFIX = "OBVYR_"

    /**
     * Exact names only. Prefix matching is deliberately avoided: CI_JOB_TOKEN and
     * similar secrets share prefixes with harmless CI identifiers.
     */
    val ENV_ALLOWLIST: Set<String> = setOf(
        "CI",
        "CONTINUOUS_INTEGRATION",
        // GitHub Actions
        "GITHUB_ACTIONS",
        "GITHUB_RUN_ID",
        "GITHUB_RUN_NUMBER",
        "GITHUB_WORKFLOW",
        "GITHUB_REPOSITORY",
        "GITHUB_SHA",
        "GITHUB_REF_NAME",
        // GitLab CI
        "GITLAB_CI",
        "CI_PIPELINE_ID",
        "CI_JOB_ID",
        "CI_COMMIT_SHA",
        "CI_COMMIT_REF_NAME",
        // Jenkins
        "JENKINS_URL",
        "BUILD_NUMBER",
        // Buildkite
        "BUILDKITE",
        "BUILDKITE_BUILD_NUMBER",
        // CircleCI
        "CIRCLECI",
        "CIRCLE_BUILD_NUM",
        "CIRCLE_SHA1",
    )

    /** Variable and flag names that suggest the value is a secret. */
    private val SECRET_NAME_PATTERN = Regex("token|key|password|passwd|secret|credential", RegexOption.IGNORE_CASE)

    /** Value shapes that are secrets wherever they appear. */
    private val SECRET_VALUE_PATTERNS = listOf(
        Regex("\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b"),
        Regex("\\bbearer\\s+[A-Za-z0-9._~+/=-]+", RegexOption.IGNORE_CASE),
    )

    /** name=value or name: value. Group 1 keeps the name visible. */
    private val SECRET_ASSIGNMENT_PATTERN = Regex(
        "(\\b[\\w-]*(?:token|key|password|passwd|secret|credential)[\\w-]*\\s*[=:]\\s*)\\S+",
        RegexOption.IGNORE_CASE,
    )

    /** Replaces anything in the text that matches a built-in or extra pattern. */
    fun redactText(text: String, extraPatterns: List<String> = emptyList()): String {
        var result = text
        for (pattern in SECRET_VALUE_PATTERNS) {
            result = pattern.replace(result) { REDACTED }
        }
        result = SECRET_ASSIGNMENT_PATTERN.replace(result) { it.groupValues[1] + REDACTED }
        for (pattern in extraPatterns) {
            result = Regex(pattern).replace(result) { REDACTED }
        }
        return result
    }

    /** Redacts secrets in command arguments, including a flag's separate value. */
    fun redactCommand(command: List<String>, extraPatterns: List<String> = emptyList()): List<String> {
        var previousWasSecretFlag = false
        return command.map { argument ->
            val redacted = if (previousWasSecretFlag) REDACTED else redactText(argument, extraPatterns)
            previousWasSecretFlag = argument.startsWith("-") &&
                !argument.contains("=") &&
                SECRET_NAME_PATTERN.containsMatchIn(argument)
            redacted
        }
    }

    /** Returns the subset of the environment that is safe to send. */
    fun filterEnvironment(
        environ: Map<String, String>,
        mode: String,
        include: List<String> = emptyList(),
        extraPatterns: List<String> = emptyList(),
    ): Map<String, String> {
        if (mode == ENV_MODE_NONE) return emptyMap()
        val permitted = ENV_ALLOWLIST + include
        return environ
            .filterKeys { it in permitted && !it.startsWith(ALWAYS_EXCLUDED_PREFIX) }
            .mapValues { (name, value) ->
                if (SECRET_NAME_PATTERN.containsMatchIn(name)) REDACTED else redactText(value, extraPatterns)
            }
    }
}
