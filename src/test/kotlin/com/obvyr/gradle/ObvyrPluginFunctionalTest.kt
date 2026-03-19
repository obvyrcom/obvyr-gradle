package com.obvyr.gradle

import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ObvyrPluginFunctionalTest {

    @TempDir
    lateinit var testProjectDir: File

    @BeforeEach
    fun setUp() {
        File(testProjectDir, "settings.gradle.kts").writeText(
            """
            rootProject.name = "test-project"
            """.trimIndent(),
        )
    }

    private fun writeBuildFile(content: String) {
        File(testProjectDir, "build.gradle.kts").writeText(content)
    }

    private fun writePassingJavaTest() {
        val testDir = File(testProjectDir, "src/test/java/com/example")
        testDir.mkdirs()
        File(testDir, "PassingTest.java").writeText(
            """
            package com.example;
            import org.junit.jupiter.api.Test;
            public class PassingTest {
                @Test void passes() {}
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `build succeeds when plugin is applied with no agentKey`() {
        writeBuildFile(
            """
            plugins {
                java
                id("com.obvyr.gradle")
            }
            repositories { mavenCentral() }
            dependencies {
                testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
                testRuntimeOnly("org.junit.platform:junit-platform-launcher")
            }
            tasks.named<Test>("test") {
                useJUnitPlatform()
            }
            """.trimIndent(),
        )
        writePassingJavaTest()

        val result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withArguments("test")
            .withPluginClasspath()
            .build()

        assertThat(result.task(":test")?.outcome).isIn(
            TaskOutcome.SUCCESS,
            TaskOutcome.UP_TO_DATE,
        )
        assertThat(result.output).contains("[Obvyr] No agent key configured")
    }

    @Test
    fun `build succeeds and no agentKey warning when plugin is disabled`() {
        writeBuildFile(
            """
            plugins {
                java
                id("com.obvyr.gradle")
            }
            repositories { mavenCentral() }
            dependencies {
                testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
                testRuntimeOnly("org.junit.platform:junit-platform-launcher")
            }
            tasks.named<Test>("test") {
                useJUnitPlatform()
            }
            obvyr {
                enabled = false
            }
            """.trimIndent(),
        )
        writePassingJavaTest()

        val result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withArguments("test")
            .withPluginClasspath()
            .build()

        assertThat(result.task(":test")?.outcome).isIn(
            TaskOutcome.SUCCESS,
            TaskOutcome.UP_TO_DATE,
        )
        assertThat(result.output).doesNotContain("[Obvyr] No agent key configured")
    }
}
