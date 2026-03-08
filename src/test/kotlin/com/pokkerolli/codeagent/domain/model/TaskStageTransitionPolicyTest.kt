package com.pokkerolli.codeagent.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TaskStageTransitionPolicyTest {
    private val expectedAllowedTargets = mapOf(
        TaskStage.CONVERSATION to setOf(TaskStage.PLANNING),
        TaskStage.PLANNING to setOf(TaskStage.PLANNING, TaskStage.EXECUTION),
        TaskStage.EXECUTION to setOf(TaskStage.VALIDATION),
        TaskStage.VALIDATION to setOf(TaskStage.DONE, TaskStage.PLANNING),
        TaskStage.DONE to setOf(TaskStage.CONVERSATION, TaskStage.PLANNING)
    )

    @Test
    fun allowedTargetsMatchSpecification() {
        assertEquals(TaskStage.entries.toSet(), expectedAllowedTargets.keys)

        expectedAllowedTargets.forEach { (current, expectedTargets) ->
            assertEquals(
                expectedTargets,
                TaskStageTransitionPolicy.allowedTargets(current),
                "Unexpected allowed targets for $current"
            )
        }
    }

    @Test
    fun validTransitionsAreAllowed() {
        expectedAllowedTargets.forEach { (current, expectedTargets) ->
            expectedTargets.forEach { target ->
                assertTrue(
                    TaskStageTransitionPolicy.isAllowed(current, target),
                    "Expected transition $current -> $target to be allowed"
                )
            }
        }
    }

    @Test
    fun invalidTransitionsAreRejected() {
        TaskStage.entries.forEach { current ->
            val allowedTargets = expectedAllowedTargets[current].orEmpty()
            TaskStage.entries
                .filterNot { target -> target in allowedTargets }
                .forEach { target ->
                    assertFalse(
                        TaskStageTransitionPolicy.isAllowed(current, target),
                        "Expected transition $current -> $target to be rejected"
                    )
                }
        }
    }
}
