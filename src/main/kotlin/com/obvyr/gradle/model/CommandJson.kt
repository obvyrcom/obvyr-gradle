package com.obvyr.gradle.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CommandJson(
    val command: List<String>,
    val user: String,
    @SerialName("return_code") val returnCode: Int,
    @SerialName("execution_time_ms") val executionTimeMs: Long,
    val executed: String,
    val env: Map<String, String>,
    val tags: List<String>,
)
