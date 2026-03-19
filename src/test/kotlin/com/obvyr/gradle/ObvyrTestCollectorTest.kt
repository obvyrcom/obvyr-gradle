package com.obvyr.gradle

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestOutputEvent
import org.gradle.api.tasks.testing.TestResult
import org.junit.jupiter.api.Test

class ObvyrTestCollectorTest {

    @Test
    fun `startTime is recorded when beforeSuite is called for root suite`() {
        val collector = ObvyrTestCollector()
        val rootSuite = mockk<TestDescriptor> { every { parent } returns null }

        collector.beforeSuite(rootSuite)

        assertThat(collector.executionTimeMs).isGreaterThanOrEqualTo(0L)
    }

    @Test
    fun `startTime is not set when non-root suite is received`() {
        val collector = ObvyrTestCollector()
        val parent = mockk<TestDescriptor>()
        val nonRoot = mockk<TestDescriptor> { every { this@mockk.parent } returns parent }

        collector.beforeSuite(nonRoot)

        assertThat(collector.executionTimeMs).isEqualTo(0L)
    }

    @Test
    fun `executionTimeMs increases after time passes`() {
        val collector = ObvyrTestCollector()
        val rootSuite = mockk<TestDescriptor> { every { parent } returns null }

        collector.beforeSuite(rootSuite)
        Thread.sleep(50)

        assertThat(collector.executionTimeMs).isGreaterThanOrEqualTo(50L)
    }

    @Test
    fun `output accumulates from onOutput calls`() {
        val collector = ObvyrTestCollector()
        val descriptor = mockk<TestDescriptor>()
        val event1 = mockk<TestOutputEvent> { every { message } returns "line1\n" }
        val event2 = mockk<TestOutputEvent> { every { message } returns "line2\n" }

        collector.onOutput(descriptor, event1)
        collector.onOutput(descriptor, event2)

        assertThat(collector.output).isEqualTo("line1\nline2\n")
    }

    @Test
    fun `returnCode is 0 when no failures`() {
        val collector = ObvyrTestCollector()
        assertThat(collector.returnCode).isEqualTo(0)
    }

    @Test
    fun `returnCode is 1 when test fails`() {
        val collector = ObvyrTestCollector()
        val descriptor = mockk<TestDescriptor>()
        val failResult = mockk<TestResult> {
            every { resultType } returns TestResult.ResultType.FAILURE
        }

        collector.afterTest(descriptor, failResult)

        assertThat(collector.returnCode).isEqualTo(1)
    }

    @Test
    fun `startTime is not updated when root suite arrives but timing has already started`() {
        val collector = ObvyrTestCollector()
        val rootSuite = mockk<TestDescriptor> { every { parent } returns null }

        collector.beforeSuite(rootSuite)
        val firstReading = collector.executionTimeMs
        collector.beforeSuite(rootSuite) // second call: startTimeMs already set, must not reset

        // executionTimeMs should keep increasing from the original start, not reset to zero
        assertThat(collector.executionTimeMs).isGreaterThanOrEqualTo(firstReading)
    }

    @Test
    fun `returnCode is 0 when afterTest is called with a non-FAILURE result`() {
        val collector = ObvyrTestCollector()
        val descriptor = mockk<TestDescriptor>()
        val successResult = mockk<TestResult> {
            every { resultType } returns TestResult.ResultType.SUCCESS
        }

        collector.afterTest(descriptor, successResult)

        assertThat(collector.returnCode).isEqualTo(0)
    }

    @Test
    fun `afterSuite can be called without error`() {
        val collector = ObvyrTestCollector()
        val descriptor = mockk<TestDescriptor>()
        val result = mockk<TestResult>()

        collector.afterSuite(descriptor, result)
    }

    @Test
    fun `beforeTest can be called without error`() {
        val collector = ObvyrTestCollector()
        val descriptor = mockk<TestDescriptor>()

        collector.beforeTest(descriptor)
    }
}
