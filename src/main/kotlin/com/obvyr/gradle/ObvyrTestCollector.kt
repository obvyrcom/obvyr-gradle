package com.obvyr.gradle

import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestListener
import org.gradle.api.tasks.testing.TestOutputEvent
import org.gradle.api.tasks.testing.TestOutputListener
import org.gradle.api.tasks.testing.TestResult

class ObvyrTestCollector : TestListener, TestOutputListener {
    private var startTimeMs: Long = -1
    private val outputBuffer = StringBuilder()
    private var failureCount = 0

    override fun beforeSuite(suite: TestDescriptor) {
        if (suite.parent == null && startTimeMs < 0) {
            startTimeMs = System.currentTimeMillis()
        }
    }

    override fun afterSuite(suite: TestDescriptor, result: TestResult) {}

    override fun beforeTest(testDescriptor: TestDescriptor) {}

    override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) {
        if (result.resultType == TestResult.ResultType.FAILURE) {
            failureCount++
        }
    }

    override fun onOutput(testDescriptor: TestDescriptor, outputEvent: TestOutputEvent) {
        outputBuffer.append(outputEvent.message)
    }

    val executionTimeMs: Long
        get() = if (startTimeMs < 0) 0L else System.currentTimeMillis() - startTimeMs

    val output: String
        get() = outputBuffer.toString()

    val returnCode: Int
        get() = if (failureCount > 0) 1 else 0
}
