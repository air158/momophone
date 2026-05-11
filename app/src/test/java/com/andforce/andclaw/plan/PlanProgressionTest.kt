package com.andforce.andclaw.plan

import org.junit.Assert.assertEquals
import org.junit.Test

class PlanProgressionTest {

    @Test
    fun verifierCannotMoveCurrentStepBackwards() {
        val plan = testPlan(
            currentStepId = "step-5",
            statuses = mapOf(
                "step-1" to StepStatus.DONE,
                "step-2" to StepStatus.DONE,
                "step-3" to StepStatus.DONE,
                "step-4" to StepStatus.IN_PROGRESS,
                "step-5" to StepStatus.IN_PROGRESS,
                "step-6" to StepStatus.TODO
            )
        )

        val updated = PlanProgression.applyVerification(
            plan = plan,
            verification = StepVerification(
                currentStepId = "step-4",
                currentStepStatus = "IN_PROGRESS",
                nextStepId = "step-4",
                evidence = "Visible comments still need ranking confirmation"
            ),
            appendLimited = ::appendLimitedForTest
        )

        assertEquals("step-5", updated.currentStepId)
        assertEquals(StepStatus.DONE, updated.steps.first { it.id == "step-4" }.status)
        assertEquals(StepStatus.IN_PROGRESS, updated.steps.first { it.id == "step-5" }.status)
    }

    @Test
    fun completedCurrentStepAdvancesToNextLaterRunnableStep() {
        val plan = testPlan(
            currentStepId = "step-5",
            statuses = mapOf(
                "step-1" to StepStatus.DONE,
                "step-2" to StepStatus.DONE,
                "step-3" to StepStatus.DONE,
                "step-4" to StepStatus.IN_PROGRESS,
                "step-5" to StepStatus.IN_PROGRESS,
                "step-6" to StepStatus.TODO
            )
        )

        val updated = PlanProgression.applyVerification(
            plan = plan,
            verification = StepVerification(
                currentStepId = "step-5",
                currentStepStatus = "DONE",
                nextStepId = null,
                evidence = "Comment was posted"
            ),
            appendLimited = ::appendLimitedForTest
        )

        assertEquals("step-6", updated.currentStepId)
        assertEquals(StepStatus.DONE, updated.steps.first { it.id == "step-4" }.status)
        assertEquals(StepStatus.DONE, updated.steps.first { it.id == "step-5" }.status)
        assertEquals(StepStatus.IN_PROGRESS, updated.steps.first { it.id == "step-6" }.status)
    }

    @Test
    fun verifierCannotUseNextStepIdToMoveBackwards() {
        val plan = testPlan(
            currentStepId = "step-5",
            statuses = mapOf(
                "step-1" to StepStatus.DONE,
                "step-2" to StepStatus.DONE,
                "step-3" to StepStatus.DONE,
                "step-4" to StepStatus.IN_PROGRESS,
                "step-5" to StepStatus.IN_PROGRESS,
                "step-6" to StepStatus.TODO
            )
        )

        val updated = PlanProgression.applyVerification(
            plan = plan,
            verification = StepVerification(
                currentStepId = "step-4",
                currentStepStatus = "DONE",
                nextStepId = "step-4",
                evidence = "Earlier step is now confirmed"
            ),
            appendLimited = ::appendLimitedForTest
        )

        assertEquals("step-5", updated.currentStepId)
        assertEquals(StepStatus.DONE, updated.steps.first { it.id == "step-4" }.status)
        assertEquals(StepStatus.IN_PROGRESS, updated.steps.first { it.id == "step-5" }.status)
    }

    private fun testPlan(currentStepId: String, statuses: Map<String, StepStatus>): AgentPlan {
        val steps = (1..6).map { number ->
            val id = "step-$number"
            PlanStep(
                id = id,
                title = "Step $number",
                description = "Step $number",
                status = statuses[id] ?: StepStatus.TODO
            )
        }
        return AgentPlan(
            id = "test-plan",
            goal = "test",
            status = PlanStatus.RUNNING,
            createdAt = 0L,
            updatedAt = 0L,
            summary = "test",
            steps = steps,
            currentStepId = currentStepId
        )
    }

    private fun appendLimitedForTest(items: List<String>, item: String): List<String> =
        (items + item).takeLast(40)
}
