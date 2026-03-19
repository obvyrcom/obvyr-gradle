# Obvyr Gradle Plugin

A Gradle plugin that hooks into test task execution to collect and submit test metadata to the Obvyr observability platform.

## Overview

The plugin automatically wraps every `Test` task in your project. After each test run it captures execution time, return code, console output, JUnit XML reports, and any configured attachments, then submits them as a compressed archive to the Obvyr API.

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
    user           = "alice"                 // default: system user.name
    tags           = listOf("ci", "unit")    // default: empty
    timeout        = 10.0                    // seconds; default shown
    verifySsl      = true                    // default shown
    attachmentPaths = listOf("build/reports/coverage/coverage.xml")
    enabled        = true                    // default shown
}
```

### Property Reference

| Property          | Type           | Default            | Env var                  | Description                                                                                                                                                                                                                                                                                              |
| ----------------- | -------------- | ------------------ | ------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `agentKey`        | `String`       | —                  | `OBVYR_API_KEY`          | Authentication token for the Obvyr API. Required; plugin logs a warning and skips submission if absent.                                                                                                                                                                                                  |
| `user`            | `String`       | system `user.name` | `OBVYR_USER`             | Username attached to each observation.                                                                                                                                                                                                                                                                   |
| `tags`            | `List<String>` | `[]`               | `OBVYR_TAGS`             | Labels applied to every observation from this project. Env var accepts a comma-separated list.                                                                                                                                                                                                           |
| `timeout`         | `Double`       | `10.0`             | `OBVYR_TIMEOUT`          | HTTP call timeout in seconds.                                                                                                                                                                                                                                                                            |
| `verifySsl`       | `Boolean`      | `true`             | `OBVYR_VERIFY_SSL`       | Set to `false` to disable TLS certificate verification (development only).                                                                                                                                                                                                                               |
| `attachmentPaths` | `List<String>` | `[]`               | `OBVYR_ATTACHMENT_PATHS` | Additional files to include in the archive. Supports glob patterns (`*.xml`, `reports/**/*.json`). Relative paths are resolved against the project directory. Only text-based file types are included (xml, json, yaml, yml, csv, txt, html, htm, log). Env var accepts a comma-separated list of paths. |
| `enabled`         | `Boolean`      | `true`             | —                        | Set to `false` to disable the plugin without removing it.                                                                                                                                                                                                                                                |

### Recommended Configuration

For most projects, no DSL block is needed at all — configure the three environment-specific values via environment variables and let everything else use its default:

```bash
export OBVYR_API_KEY="agt_your_token_here"
export OBVYR_USER="alice"
export OBVYR_TAGS="local"
```

Set these differently per environment (e.g. `OBVYR_TAGS=ci` and `OBVYR_USER=ci-runner` in your CI pipeline). All other settings use their defaults.

### Environment Variables

All string properties fall back to their corresponding environment variable when the DSL value is not set:

```bash
export OBVYR_API_KEY="agt_your_token_here"
export OBVYR_USER="ci-runner"
export OBVYR_TAGS="ci,github-actions"
export OBVYR_TIMEOUT="30.0"
export OBVYR_VERIFY_SSL="true"
export OBVYR_ATTACHMENT_PATHS="build/reports/coverage/coverage.xml,build/reports/lint/lint.html"
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
