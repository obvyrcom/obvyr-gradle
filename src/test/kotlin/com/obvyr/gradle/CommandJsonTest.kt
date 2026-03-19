package com.obvyr.gradle

import com.obvyr.gradle.model.CommandJson
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CommandJsonTest {

    private val subject = CommandJson(
        command = listOf("gradle", ":test"),
        user = "testuser",
        returnCode = 42,
        executionTimeMs = 1234L,
        executed = "2026-03-16T12:00:00Z",
        env = mapOf("PATH" to "/usr/bin"),
        tags = listOf("ci", "unit"),
    )

    @Test
    fun `command property returns the command list`() {
        assertThat(subject.command).isEqualTo(listOf("gradle", ":test"))
    }

    @Test
    fun `user property returns the user`() {
        assertThat(subject.user).isEqualTo("testuser")
    }

    @Test
    fun `returnCode property returns the return code`() {
        assertThat(subject.returnCode).isEqualTo(42)
    }

    @Test
    fun `executionTimeMs property returns the execution time`() {
        assertThat(subject.executionTimeMs).isEqualTo(1234L)
    }

    @Test
    fun `executed property returns the timestamp`() {
        assertThat(subject.executed).isEqualTo("2026-03-16T12:00:00Z")
    }

    @Test
    fun `env property returns the environment map`() {
        assertThat(subject.env).isEqualTo(mapOf("PATH" to "/usr/bin"))
    }

    @Test
    fun `tags property returns the tags list`() {
        assertThat(subject.tags).isEqualTo(listOf("ci", "unit"))
    }
}
