package com.andforce.andclaw.plan

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
