package com.obvyr.gradle

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

interface ObvyrExtension {
    val agentKey: Property<String>
    val user: Property<String>
    val apiUrl: Property<String>
    val tags: ListProperty<String>
    val timeout: Property<Double>
    val verifySsl: Property<Boolean>
    val attachmentPaths: ListProperty<String>
    val enabled: Property<Boolean>
    val envMode: Property<String>
    val envInclude: ListProperty<String>
    val redactPatterns: ListProperty<String>
    val dryRun: Property<Boolean>
}
