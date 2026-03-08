package com.pokkerolli.codeagent.domain.model

object TaskStageTransitionPolicy {
    private val allowedTargetsByStage: Map<TaskStage, Set<TaskStage>> = mapOf(
        TaskStage.CONVERSATION to setOf(TaskStage.PLANNING),
        TaskStage.PLANNING to setOf(TaskStage.PLANNING, TaskStage.EXECUTION),
        TaskStage.EXECUTION to setOf(TaskStage.VALIDATION),
        TaskStage.VALIDATION to setOf(TaskStage.DONE, TaskStage.PLANNING),
        TaskStage.DONE to setOf(TaskStage.CONVERSATION, TaskStage.PLANNING)
    )

    fun isAllowed(current: TaskStage, target: TaskStage): Boolean {
        return allowedTargetsByStage[current]?.contains(target) == true
    }

    fun allowedTargets(current: TaskStage): Set<TaskStage> {
        return allowedTargetsByStage[current].orEmpty()
    }
}
