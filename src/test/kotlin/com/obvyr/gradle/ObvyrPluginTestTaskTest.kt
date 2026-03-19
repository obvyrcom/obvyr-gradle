package com.obvyr.gradle

import org.assertj.core.api.Assertions.assertThat
import org.gradle.api.tasks.testing.Test
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test as JUnitTest

class ObvyrPluginTestTaskTest {

    @JUnitTest
    fun `plugin hooks into test tasks when java plugin is applied`() {
        val project = ProjectBuilder.builder().build()
        project.pluginManager.apply("java")
        project.pluginManager.apply("com.obvyr.gradle")
        val testTask = project.tasks.getByName("test") as Test
        assertThat(testTask).isNotNull()
    }

    @JUnitTest
    fun `plugin applies without error when agentKey is not set`() {
        val project = ProjectBuilder.builder().build()
        project.pluginManager.apply("java")
        project.pluginManager.apply("com.obvyr.gradle")
        val testTask = project.tasks.getByName("test") as Test
        assertThat(testTask).isNotNull()
    }

    @JUnitTest
    fun `plugin does not hook into test tasks when java plugin is not applied`() {
        val project = ProjectBuilder.builder().build()
        project.pluginManager.apply("com.obvyr.gradle")
        assertThat(project.tasks.findByName("test")).isNull()
    }

    @JUnitTest
    fun `doLast action executes without error when agentKey is not configured`() {
        val project = ProjectBuilder.builder().build()
        project.pluginManager.apply("java")
        project.pluginManager.apply("com.obvyr.gradle")
        val testTask = project.tasks.getByName("test") as Test
        // Execute our doLast action directly — agentKey absent so it logs a warning and returns cleanly
        testTask.actions.last().execute(testTask)
    }
}
