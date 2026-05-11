package com.andforce.andclaw.plan

import android.content.Context
import com.andforce.andclaw.model.AiAction
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class PlanManager(context: Context) {
    private val appContext = context.applicationContext
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val rootDir = File(appContext.filesDir, "agent_plans")

    fun createPlan(goal: String): AgentPlan {
        rootDir.mkdirs()
        val now = System.currentTimeMillis()
        val id = buildPlanId(now, goal)
        val plan = AgentPlan(
            id = id,
            goal = goal,
            status = PlanStatus.RUNNING,
            createdAt = now,
            updatedAt = now,
            summary = goal.take(120),
            currentStepId = "step-1",
            steps = listOf(
                PlanStep(
                    id = "step-1",
                    title = "Observe current device state",
                    description = "Capture the current UI state and identify the safest next route.",
                    status = StepStatus.IN_PROGRESS,
                    type = StepType.OBSERVE
                ),
                PlanStep(
                    id = "step-2",
                    title = "Execute toward the goal",
                    description = "Use Android actions step by step, updating the plan as new UI state appears.",
                    type = StepType.UI_ACTION
                ),
                PlanStep(
                    id = "step-3",
                    title = "Verify completion",
                    description = "Check that the user's requested outcome is actually complete before finishing.",
                    type = StepType.VERIFY
                )
            )
        )
        save(plan)
        return plan
    }

    fun recordAction(plan: AgentPlan?, action: AiAction): AgentPlan? {
        if (plan == null) return null
        val stepId = action.currentStepId ?: plan.currentStepId ?: nextRunnableStep(plan)?.id
        val now = System.currentTimeMillis()
        val updatedSteps = plan.steps.map { step ->
            if (step.id != stepId) {
                step
            } else {
                step.copy(
                    status = StepStatus.IN_PROGRESS,
                    attempts = step.attempts + 1,
                    evidence = appendLimited(
                        step.evidence,
                        "Action: ${action.type}; progress=${action.progress.orEmpty()}; reason=${action.reason.orEmpty()}"
                    )
                )
            }
        }
        return plan.copy(
            status = PlanStatus.RUNNING,
            updatedAt = now,
            currentStepId = stepId,
            steps = updatedSteps,
            memory = plan.memory.copy(
                decisions = appendLimited(
                    plan.memory.decisions,
                    "Selected action ${action.type} for ${stepId ?: "unknown step"}"
                )
            )
        ).also(::save)
    }

    fun recordObservation(plan: AgentPlan?, message: String): AgentPlan? {
        if (plan == null || message.isBlank()) return plan
        return plan.copy(
            updatedAt = System.currentTimeMillis(),
            memory = plan.memory.copy(observations = appendLimited(plan.memory.observations, message))
        ).also(::save)
    }

    fun recordFailure(plan: AgentPlan?, message: String, terminal: Boolean = false): AgentPlan? {
        if (plan == null) return null
        val stepId = plan.currentStepId
        val updatedSteps = plan.steps.map { step ->
            if (step.id == stepId) {
                step.copy(
                    status = if (terminal) StepStatus.FAILED else StepStatus.BLOCKED,
                    lastError = message
                )
            } else {
                step
            }
        }
        return plan.copy(
            status = if (terminal) PlanStatus.FAILED else PlanStatus.BLOCKED,
            updatedAt = System.currentTimeMillis(),
            steps = updatedSteps,
            memory = plan.memory.copy(blockers = appendLimited(plan.memory.blockers, message))
        ).also(::save)
    }

    fun markDone(plan: AgentPlan?, reason: String?): AgentPlan? {
        if (plan == null) return null
        val now = System.currentTimeMillis()
        val updatedSteps = plan.steps.map { step ->
            if (step.status == StepStatus.DONE || step.status == StepStatus.SKIPPED) {
                step
            } else {
                step.copy(
                    status = StepStatus.DONE,
                    evidence = appendLimited(step.evidence, reason ?: "Task completed")
                )
            }
        }
        return plan.copy(
            status = PlanStatus.DONE,
            updatedAt = now,
            steps = updatedSteps,
            currentStepId = null
        ).also(::save)
    }

    fun markCancelled(plan: AgentPlan?, reason: String?): AgentPlan? {
        if (plan == null || plan.status == PlanStatus.DONE || plan.status == PlanStatus.FAILED) return plan
        return plan.copy(
            status = PlanStatus.CANCELLED,
            updatedAt = System.currentTimeMillis(),
            memory = plan.memory.copy(blockers = appendLimited(plan.memory.blockers, reason ?: "Agent stopped"))
        ).also(::save)
    }

    fun toPromptContext(plan: AgentPlan?): String? {
        if (plan == null) return null
        val current = plan.steps.firstOrNull { it.id == plan.currentStepId }
        val stepLines = plan.steps.joinToString("\n") { step ->
            "- ${step.id} [${step.status}]: ${step.title} (${step.type})"
        }
        return """
Long-term plan id: ${plan.id}
Plan status: ${plan.status}
Goal: ${plan.goal}
Current step: ${current?.id ?: "none"} - ${current?.title ?: "none"}
Steps:
$stepLines

Use current_step_id in your JSON response. If the current step is complete, continue with the next unfinished step. If the plan no longer fits the observed screen, explain the blocker in reason and choose a safer next action.
""".trimIndent()
    }

    fun planDir(plan: AgentPlan): File = File(rootDir, plan.id)

    private fun save(plan: AgentPlan) {
        val dir = planDir(plan)
        dir.mkdirs()
        File(dir, "plan.json").writeText(gson.toJson(plan))
        File(dir, "plan.md").writeText(renderMarkdown(plan))
    }

    private fun renderMarkdown(plan: AgentPlan): String {
        val updated = formatTime(plan.updatedAt)
        val created = formatTime(plan.createdAt)
        val steps = plan.steps.joinToString("\n\n") { step ->
            val box = when (step.status) {
                StepStatus.DONE -> "x"
                else -> " "
            }
            val evidence = if (step.evidence.isEmpty()) {
                ""
            } else {
                "\n  Evidence:\n" + step.evidence.takeLast(5).joinToString("\n") { "  - $it" }
            }
            val error = step.lastError?.let { "\n  Last error: $it" }.orEmpty()
            "- [$box] ${step.id}: ${step.title}\n  Status: ${step.status}\n  Type: ${step.type}\n  Attempts: ${step.attempts}\n  Description: ${step.description}$evidence$error"
        }
        val observations = plan.memory.observations.takeLast(10).joinToString("\n") { "- $it" }.ifBlank { "- None" }
        val decisions = plan.memory.decisions.takeLast(10).joinToString("\n") { "- $it" }.ifBlank { "- None" }
        val blockers = plan.memory.blockers.takeLast(10).joinToString("\n") { "- $it" }.ifBlank { "- None" }

        return """
# Plan: ${plan.summary}

Status: ${plan.status}
Current Step: ${plan.currentStepId ?: "none"}
Created: $created
Updated: $updated

## Goal
${plan.goal}

## Steps

$steps

## Observations
$observations

## Decisions
$decisions

## Blockers
$blockers
""".trimIndent() + "\n"
    }

    private fun nextRunnableStep(plan: AgentPlan): PlanStep? =
        plan.steps.firstOrNull { it.status == StepStatus.IN_PROGRESS }
            ?: plan.steps.firstOrNull { it.status == StepStatus.TODO }

    private fun appendLimited(items: List<String>, item: String, max: Int = 40): List<String> =
        (items + item.take(500)).takeLast(max)

    private fun buildPlanId(now: Long, goal: String): String {
        val time = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(now))
        val slug = goal.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9\\u4e00-\\u9fa5]+"), "-")
            .trim('-')
            .take(32)
            .ifBlank { UUID.randomUUID().toString().take(8) }
        return "${time}_$slug"
    }

    private fun formatTime(time: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(time))
}
