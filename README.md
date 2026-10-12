# Obvyr Gradle Plugin

A Gradle plugin that hooks into test task execution to collect and submit test metadata to the Obvyr observability platform.

## Overview

The plugin automatically wraps every `Test` task in your project. After each test run it captures execution time, return code, console output, JUnit XML reports, and any configured attachments, then submits them as a compressed archive to the Obvyr API. Only a documented allow-list of environment variables is sent, and secrets are redacted before upload (see [What Is Sent](#what-is-sent)).

### Design Philosophy

- **Silent by default**: No output unless there is a problem that needs attention
- **Non-blocking**: Build failures never originate from the plugin — submission errors are logged as warnings only
- **Graceful degradation**: If the agent key is absent or the API is unreachable the build continues normally
- **Configurable**: All settings can be supplied via the DSL block or environment variables

## Installation

### Plugin Portal (recommended)

```kotlin
// build.gradle.kts
plugins {
    id("com.obvyr.gradle") version "1.0.0"
}
```

```groovy
// build.gradle
plugins {
    id 'com.obvyr.gradle' version '1.0.0'
}
```

The plugin activates automatically once the `java` (or `java-library`) plugin is also applied. It requires **JVM 17** or later.

## Quick Start

### 1. Get an Agent Token

Create a CLI agent through the Obvyr web interface to obtain an authentication token.

### 2. Configure the Plugin

```kotlin
// build.gradle.kts
obvyr {
    agentKey = "agt_your_token_here"
}
```

Or supply the token via an environment variable (see [Configuration](#configuration) below) so the key is never committed to source control.

### 3. Run Your Tests

```bash
./gradlew test
```

Test results are submitted automatically at the end of every `test` task.

## Configuration

All settings can be provided through the DSL block, a corresponding environment variable, or a hardcoded default. The resolution order is **DSL > environment variable > default**.

```kotlin
// build.gradle.kts
obvyr {
    agentKey       = "agt_your_token_here"   // required; no default
    user           = "local-dev"             // default: system user.name
    tags           = listOf("ci", "unit")    // default: empty
    timeout        = 10.0                    // seconds; default shown
    verifySsl      = true                    // default shown
    attachmentPaths = listOf("build/reports/coverage/coverage.xml")
    enabled        = true                    // default shown
    envMode        = "allowlist"             // or "none"; default shown
    envInclude     = listOf("BUILD_FLAVOUR") // extra variables to send; default: empty
    redactPatterns = listOf("ACME-\\d+")      // extra regexes to redact; default: empty
    dryRun         = false                   // default shown
}
```

### Property Reference

| Property          | Type           | Default            | Env var                  | Description                                                                                                                                                                                                                                                                                              |
| ----------------- | -------------- | ------------------ | ------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `agentKey`        | `String`       | —                  | `OBVYR_API_KEY`          | Authentication token for the Obvyr API. Required; plugin logs a warning and skips submission if absent.                                                                                                                                                                                                  |
| `user`            | `String`       | system `user.name` | `OBVYR_USER`             | Execution context identifier attached to each observation. Use a value that describes _what_ is running the build, not _who_ — e.g. `local-dev`, `github-ci`, `jenkins-prod`.                                                                                                                            |
| `tags`            | `List<String>` | `[]`               | `OBVYR_TAGS`             | Labels applied to every observation from this project. Env var accepts a comma-separated list.                                                                                                                                                                                                           |
| `timeout`         | `Double`       | `10.0`             | `OBVYR_TIMEOUT`          | HTTP call timeout in seconds.                                                                                                                                                                                                                                                                            |
| `verifySsl`       | `Boolean`      | `true`             | `OBVYR_VERIFY_SSL`       | Set to `false` to disable TLS certificate verification (development only).                                                                                                                                                                                                                               |
| `attachmentPaths` | `List<String>` | `[]`               | `OBVYR_ATTACHMENT_PATHS` | Additional files to include in the archive. Supports glob patterns (`*.xml`, `reports/**/*.json`). Relative paths are resolved against the project directory. Only text-based file types are included (xml, json, yaml, yml, csv, txt, html, htm, log). Env var accepts a comma-separated list of paths. |
| `enabled`         | `Boolean`      | `true`             | —                        | Set to `false` to disable the plugin without removing it.                                                                                                                                                                                                                                                |
| `envMode`         | `String`       | `allowlist`        | `OBVYR_ENV_MODE`         | `allowlist` sends only the CI variables listed in [What Is Sent](#what-is-sent) plus `envInclude`. `none` sends no environment at all. Any other value is treated as `none` and logs a warning.                                                                                                          |
| `envInclude`      | `List<String>` | `[]`               | `OBVYR_ENV_INCLUDE`      | Names of extra environment variables to send. `OBVYR_*` variables are never sent, even if named here. Env var accepts a comma-separated list.                                                                                                                                                            |
| `redactPatterns`  | `List<String>` | `[]`               | `OBVYR_REDACT_PATTERNS`  | Extra regular expressions to redact from environment values, command arguments and output, in addition to the built-in patterns. Env var accepts a comma-separated list, so a pattern cannot contain a comma; write `[,]` instead.                                                                       |
| `dryRun`          | `Boolean`      | `false`            | `OBVYR_DRY_RUN`          | Print exactly what would be sent and make no network call. No agent key is needed.                                                                                                                                                                                                                       |

### What Is Sent

Each `test` task submits one archive containing:

- **`command.json`**: the command (`gradle <task path>`), the `user`, return code, execution time, timestamp, `tags`, and the environment described below.
- **`output.txt`**: the console output of the test task, with secrets redacted.
- **Attachments**: the JUnit XML reports and any files matched by `attachmentPaths`.

**Environment variables.** By default only these CI identifiers are sent. Every other variable is omitted:

`CI`, `CONTINUOUS_INTEGRATION`, `GITHUB_ACTIONS`, `GITHUB_RUN_ID`, `GITHUB_RUN_NUMBER`, `GITHUB_WORKFLOW`, `GITHUB_REPOSITORY`, `GITHUB_SHA`, `GITHUB_REF_NAME`, `GITLAB_CI`, `CI_PIPELINE_ID`, `CI_JOB_ID`, `CI_COMMIT_SHA`, `CI_COMMIT_REF_NAME`, `JENKINS_URL`, `BUILD_NUMBER`, `BUILDKITE`, `BUILDKITE_BUILD_NUMBER`, `CIRCLECI`, `CIRCLE_BUILD_NUM`, `CIRCLE_SHA1`

Variables beginning with `OBVYR_` (including your agent key) are never sent. Add further variables with `envInclude`, or set `envMode = "none"` to send no environment at all.

**Redaction.** Before upload, matches are replaced with `[REDACTED]` in environment values, command arguments and output. The built-in patterns cover:

- Variables and flags whose name contains `token`, `key`, `password`, `passwd`, `secret` or `credential` (the value is redacted)
- Assignments such as `password=value` or `secret: value`
- AWS access key IDs (`AKIA…`, `ASIA…`)
- Bearer tokens

Redaction is best-effort pattern matching, not a guarantee. An invalid `redactPatterns` entry logs a warning and nothing is submitted.

**What is not changed:**

- Gradle's own console output is displayed unredacted. Only the uploaded copy is redacted.
- Attachments, including JUnit XML reports (which contain captured test stdout and stderr), are uploaded as they are, without redaction. Only attach files that are safe to share.

**Dry run.** Set `dryRun = true` (or `OBVYR_DRY_RUN=true`) to run the tests as normal and print the exact `command.json` and `output.txt` that would be uploaded, with attachments listed by name and size. Nothing is sent.

```bash
OBVYR_DRY_RUN=true ./gradlew test
```

**Upgrading.** Earlier versions sent the entire environment. If you relied on specific variables appearing in Obvyr, add them to `envInclude`.

### Recommended Configuration

For most projects, no DSL block is needed at all — configure the three environment-specific values via environment variables and let everything else use its default:

```bash
export OBVYR_API_KEY="agt_your_token_here"
export OBVYR_USER="local-dev"
export OBVYR_TAGS="local"
```

Set these differently per environment — for example, in your CI pipeline:

```bash
export OBVYR_API_KEY="agt_your_token_here"
export OBVYR_USER="github-ci"
export OBVYR_TAGS="ci"
```

`OBVYR_USER` should identify the _execution context_ (what is running the build), not an individual. All other settings use their defaults.

### Environment Variables

All string properties fall back to their corresponding environment variable when the DSL value is not set:

```bash
export OBVYR_API_KEY="agt_your_token_here"
export OBVYR_USER="ci-runner"
export OBVYR_TAGS="ci,github-actions"
export OBVYR_TIMEOUT="30.0"
export OBVYR_VERIFY_SSL="true"
export OBVYR_ATTACHMENT_PATHS="build/reports/coverage/coverage.xml,build/reports/lint/lint.html"
export OBVYR_ENV_MODE="allowlist"
export OBVYR_ENV_INCLUDE="BUILD_FLAVOUR,REGION"
export OBVYR_REDACT_PATTERNS="ACME-\\d+"
export OBVYR_DRY_RUN="false"
```

## Integration Examples

### GitHub Actions

```yaml
- name: Run tests
  env:
    OBVYR_API_KEY: ${{ secrets.OBVYR_API_KEY }}
  run: ./gradlew test
```

With tags for environment context:

```kotlin
// build.gradle.kts
obvyr {
    tags = listOf("ci", "github-actions")
}
```

### GitLab CI

```yaml
test:
  script:
    - ./gradlew test
  variables:
    OBVYR_API_KEY: $OBVYR_API_KEY
```

### Jenkins Pipeline

```groovy
environment {
    OBVYR_API_KEY = credentials('obvyr-agent-key')
}
stages {
    stage('Test') {
        steps {
            sh './gradlew test'
        }
    }
}
```

### Multi-Module Projects

The plugin applies to every `Test` task in every subproject. Configure it once in the root `build.gradle.kts`:

```kotlin
// root build.gradle.kts
subprojects {
    apply(plugin = "com.obvyr.gradle")
    configure<com.obvyr.gradle.ObvyrExtension> {
        tags = listOf("ci")
    }
}
```

Or configure it per subproject for different tag sets:

```kotlin
// api/build.gradle.kts
obvyr {
    tags = listOf("api", "integration")
}

// core/build.gradle.kts
obvyr {
    tags = listOf("core", "unit")
}
```

### Disabling for Local Builds

```kotlin
// build.gradle.kts
obvyr {
    enabled = System.getenv("CI") != null
}
```

## Troubleshooting

### No agent key configured

```
[Obvyr] No agent key configured. Set obvyr { agentKey = "..." } or OBVYR_API_KEY env var.
```

Set the `agentKey` property in the DSL block or export `OBVYR_API_KEY` as an environment variable.

### Submission failed

```
[Obvyr] Submission failed. Check agent key and API URL.
```

Verify that the agent key is valid. The build continues regardless.

### Error submitting test data

```
[Obvyr] Error submitting test data: <message>
```

An unexpected exception occurred during submission. The build is not affected. Check network connectivity and the configured `apiUrl`.

### Incompatible with configuration cache

The plugin marks itself as incompatible with Gradle's configuration cache (`notCompatibleWithConfigurationCache`). This is expected behaviour in version 1.0 — it does not affect correctness or build output.

## License

Copyright © 2026 Obvyr Pty Ltd. All Rights Reserved.
