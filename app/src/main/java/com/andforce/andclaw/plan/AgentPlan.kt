package com.andforce.andclaw.plan

import com.google.gson.annotations.SerializedName

data class AgentPlan(
    val id: String,
    val goal: String,
    val status: PlanStatus = PlanStatus.PLANNING,
    val createdAt: Long,
    val updatedAt: Long,
    val summary: String,
    val steps: List<PlanStep>,
    val currentStepId: String? = null,
    val memory: PlanMemory = PlanMemory()
)

data class PlanStep(
    val id: String,
    val title: String,
    val description: String,
    val status: StepStatus = StepStatus.TODO,
    val type: StepType = StepType.UI_ACTION,
    val evidence: List<String> = emptyList(),
    val attempts: Int = 0,
    val lastError: String? = null
)

data class PlanMemory(
    val observations: List<String> = emptyList(),
    val decisions: List<String> = emptyList(),
    val blockers: List<String> = emptyList()
)

enum class PlanStatus {
    PLANNING,
    RUNNING,
    PAUSED,
    BLOCKED,
    DONE,
    FAILED,
    CANCELLED
}

enum class StepStatus {
    TODO,
    IN_PROGRESS,
    DONE,
    BLOCKED,
    FAILED,
    SKIPPED
}

enum class StepType {
    OBSERVE,
    UI_ACTION,
    API_ACTION,
    VERIFY,
    DECISION
}

data class PlanDraft(
    val summary: String? = null,
    val steps: List<PlanDraftStep> = emptyList()
)

data class PlanDraftStep(
    val id: String? = null,
    val title: String? = null,
    val description: String? = null,
    val type: String? = null
)

data class PlanPatch(
    val reason: String? = null,
    val updates: List<PlanPatchUpdate> = emptyList()
)

data class PlanPatchUpdate(
    @SerializedName("step_id")
    val stepId: String? = null,
    val status: String? = null,
    val evidence: String? = null,
    @SerializedName("last_error")
    val lastError: String? = null,
    @SerializedName("insert_after")
    val insertAfter: String? = null,
    val step: PlanDraftStep? = null,
    @SerializedName("current_step_id")
    val currentStepId: String? = null
)

data class StepVerification(
    @SerializedName("current_step_id")
    val currentStepId: String? = null,
    @SerializedName("current_step_status")
    val currentStepStatus: String? = null,
    @SerializedName("next_step_id")
    val nextStepId: String? = null,
    val evidence: String? = null,
    val blocker: String? = null,
    @SerializedName("task_complete")
    val taskComplete: Boolean = false
)
