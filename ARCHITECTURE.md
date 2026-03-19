# Architecture

## Overview

The plugin hooks into every Gradle `Test` task at apply-time. For each task it installs a `TestListener`/`TestOutputListener` pair that collects timing and output, then registers a `doLast` action that builds and submits a `tar.zst` archive to the Obvyr API.

## Source Layout

```
src/main/kotlin/com/obvyr/gradle/
├── ObvyrPlugin.kt               # Plugin entry point
├── ObvyrExtension.kt            # DSL configuration interface
├── ObvyrTestCollector.kt        # Collects runtime data during the test run
├── ObvyrSubmitAction.kt         # Orchestrates submission after the run
├── archive/
│   └── ArchiveBuilder.kt        # Builds tar.zst archive in memory
├── http/
│   └── ObvyrApiClient.kt        # OkHttp3 multipart POST to /collect
└── model/
    └── CommandJson.kt           # Serialisable observation metadata
```

## Data Flow

```
Gradle test run
    │
    ├── ObvyrTestCollector (TestListener + TestOutputListener)
    │       Records: start time, console output, failure count
    │
    └── doLast → ObvyrSubmitAction.execute()
            │
            ├── Resolve config (DSL > env var > default)
            ├── Build CommandJson (command, user, return_code, execution_time_ms, executed, env, tags)
            ├── Gather attachments
            │       JUnit XML from task results dir  (newest-first, 5 MB / file, 10 MB total)
            │       Configured attachmentPaths       (same limits)
            ├── ArchiveBuilder.build()  →  tar.zst ByteArray
            └── ObvyrApiClient.submit()  →  POST /collect  (multipart, Authorization: Bearer)
```

## Key Classes

### ObvyrPlugin

Registers the `ObvyrExtension` DSL block and, once the `java` plugin is present, calls `configureEach` on all `Test` tasks. For each task it creates an `ObvyrTestCollector`, attaches it as a listener, and adds a `doLast` action backed by `ObvyrSubmitAction`. Marked `notCompatibleWithConfigurationCache` (v1 limitation).

### ObvyrExtension

Gradle `interface` with eight `Property<T>` / `ListProperty<T>` fields. Convention defaults are set in `ObvyrPlugin.apply()`:

| Property | Default | Env var |
|---|---|---|
| `agentKey` | — (required) | `OBVYR_API_KEY` |
| `user` | `System.getProperty("user.name")` | `OBVYR_USER` |
| `apiUrl` | `"https://api.obvyr.com"` | `OBVYR_API_URL` |
| `tags` | `[]` | `OBVYR_TAGS` (comma-separated) |
| `timeout` | `10.0` s | `OBVYR_TIMEOUT` |
| `verifySsl` | `true` | `OBVYR_VERIFY_SSL` |
| `attachmentPaths` | `[]` | `OBVYR_ATTACHMENT_PATHS` (comma-separated) |
| `enabled` | `true` | — |

### ObvyrTestCollector

Implements `TestListener` + `TestOutputListener`. Records `startTimeMs` on the first root suite descriptor (`parent == null`). Subsequent root-suite callbacks are ignored so the clock is never reset mid-run. Exposes `executionTimeMs`, `output` (concatenated stdout/stderr), and `returnCode` (0 unless any `FAILURE` result is seen).

### ObvyrSubmitAction

Resolves each property with the helper chain `DSL property → System.getenv(envVar) → hardcoded default`. Env lookup is injectable via the `envLookup` constructor parameter (default `System::getenv`) to allow testing without `mockkStatic`. String properties use `resolveOrNull`; `Double`, `Boolean`, and `List<String>` have dedicated typed helpers (`resolveDouble`, `resolveBoolean`, `resolveList`). Comma-separated env vars (`OBVYR_TAGS`, `OBVYR_ATTACHMENT_PATHS`) are split and trimmed by `resolveList`.

Attachment gathering has two phases:
1. **JUnit XML results** — walks `testResultsDir` for `*.xml` files, sorted newest-first.
2. **Configured paths** — each `attachmentPaths` entry is expanded via `resolvePattern()` (supports `*` and `?` glob wildcards), filtered to text-only extensions (xml, json, yaml, yml, csv, txt, html, htm, log), then sorted by priority (xml/json = high, yaml/yml/csv = medium, others = low) before greedy selection.

Both phases share the 5 MB per-file and 10 MB total limits.

### ArchiveBuilder

Writes a tar stream into a `ByteArrayOutputStream`, then wraps it with `ZstdOutputStream` (Commons Compress + zstd-jni). Entries: `command.json` (always), `output.txt` (when non-blank), `attachment/<name>` (for each pair). The tar is built fully in memory before zstd compression starts — this matches the approach used by `obvyr_cli`.

### ObvyrApiClient

Constructs an `OkHttpClient` with a call timeout. When `verifySsl = false`, installs a trust-all `X509TrustManager` (intended for development environments only). Submits a `multipart/form-data` body with a single part named `archive`, filename `artifacts.tar.zst`. Returns `true` on any 2xx response; returns `false` (never throws) on non-2xx or network errors.

### CommandJson

`@Serializable` data class using `@SerialName` annotations for snake_case field names (`return_code`, `execution_time_ms`). Serialised with `Json { encodeDefaults = true }`.

## Testing Strategy

| Layer | Tool | Coverage |
|---|---|---|
| Unit | JUnit 5 + MockK + AssertJ | All classes |
| HTTP contract | OkHttp `MockWebServer` | `ObvyrApiClient`, `ObvyrSubmitAction`, `ArchiveContractTest` |
| HTTPS (self-signed) | `okhttp-tls` `HeldCertificate` | `verifySsl = false` path |
| Plugin integration | Gradle `ProjectBuilder` | `ObvyrPlugin`, `ObvyrExtension` |
| Functional | Gradle TestKit `GradleRunner` | End-to-end task lifecycle |

JaCoCo enforces 100% line / branch / method coverage. Two structurally dead class-file fragments are excluded via `classDirectories` in `build.gradle.kts`:

- `ObvyrApiClient$buildClient$trustAllCerts$1` — `checkClientTrusted` is only invoked server-side during mutual TLS; unreachable in a client-only context.
- `CommandJson$$serializer` — `kotlinx-serialization` generates a deserialise path that the plugin never uses.
