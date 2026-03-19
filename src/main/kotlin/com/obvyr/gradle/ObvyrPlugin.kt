package com.obvyr.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.tasks.testing.Test

class ObvyrPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("obvyr", ObvyrExtension::class.java)

        extension.apiUrl.convention("https://api.obvyr.com")
        extension.timeout.convention(10.0)
        extension.verifySsl.convention(true)
        extension.enabled.convention(true)
        extension.user.convention(System.getProperty("user.name", "unknown"))

        project.plugins.withType(JavaPlugin::class.java) {
            project.tasks.withType(Test::class.java).configureEach { testTask ->
                val collector = ObvyrTestCollector()
                testTask.addTestListener(collector)
                testTask.addTestOutputListener(collector)
                @Suppress("UnstableApiUsage")
                testTask.notCompatibleWithConfigurationCache("Obvyr plugin v1")
                testTask.doLast {
                    ObvyrSubmitAction(
                        extension,
                        collector,
                        testTask.reports.junitXml.outputLocation.asFile.get(),
                        project.projectDir,
                        project.logger,
                    ).execute(testTask)
                }
            }
        }
    }
}
