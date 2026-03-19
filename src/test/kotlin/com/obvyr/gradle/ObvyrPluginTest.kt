package com.obvyr.gradle

import org.assertj.core.api.Assertions.assertThat
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test

class ObvyrPluginTest {

    @Test
    fun `plugin can be applied to project`() {
        val project = ProjectBuilder.builder().build()
        project.pluginManager.apply("com.obvyr.gradle")
        assertThat(project.plugins.hasPlugin("com.obvyr.gradle")).isTrue()
    }

    @Test
    fun `plugin creates obvyr extension`() {
        val project = ProjectBuilder.builder().build()
        project.pluginManager.apply("com.obvyr.gradle")
        assertThat(project.extensions.findByName("obvyr")).isNotNull()
    }

    @Test
    fun `extension has agentKey property`() {
        val project = ProjectBuilder.builder().build()
        project.pluginManager.apply("com.obvyr.gradle")
        val extension = project.extensions.getByName("obvyr") as ObvyrExtension
        assertThat(extension.agentKey).isNotNull()
    }

    @Test
    fun `extension has all required properties`() {
        val project = ProjectBuilder.builder().build()
        project.pluginManager.apply("com.obvyr.gradle")
        val extension = project.extensions.getByName("obvyr") as ObvyrExtension
        assertThat(extension.agentKey).isNotNull()
        assertThat(extension.user).isNotNull()
        assertThat(extension.apiUrl).isNotNull()
        assertThat(extension.tags).isNotNull()
        assertThat(extension.timeout).isNotNull()
        assertThat(extension.verifySsl).isNotNull()
        assertThat(extension.attachmentPaths).isNotNull()
        assertThat(extension.enabled).isNotNull()
    }

    @Test
    fun `extension defaults are set correctly`() {
        val project = ProjectBuilder.builder().build()
        project.pluginManager.apply("com.obvyr.gradle")
        val extension = project.extensions.getByName("obvyr") as ObvyrExtension
        assertThat(extension.apiUrl.get()).isEqualTo("https://api.obvyr.com")
        assertThat(extension.timeout.get()).isEqualTo(10.0)
        assertThat(extension.verifySsl.get()).isTrue()
        assertThat(extension.enabled.get()).isTrue()
    }

    @Test
    fun `DSL value overrides default`() {
        val project = ProjectBuilder.builder().build()
        project.pluginManager.apply("com.obvyr.gradle")
        val extension = project.extensions.getByName("obvyr") as ObvyrExtension
        extension.apiUrl.set("https://custom.api.com")
        assertThat(extension.apiUrl.get()).isEqualTo("https://custom.api.com")
    }
}
