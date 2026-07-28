package dev.kortex.core.prompt

import dev.kortex.core.state.PlanStep

/**
 * Final-answer composition prompt for [dev.kortex.core.pattern.SynthesizeNode]
 * (pattern 6: Planning). Gets the goal plus every plan step (description, status,
 * result) and must compose the single final answer: answer-first, concise, explicit
 * about anything a FAILED step leaves unknown, never inventing data the step results
 * don't contain.
 *
 * On revise passes [priorAnswer] and [feedback] carry the previous answer and the
 * reviewer's critique so the model re-answers from the same step results.
 */
object SynthesizePrompt {

    fun build(
        goal: String,
        steps: List<PlanStep>,
        priorAnswer: String? = null,
        feedback: String? = null,
    ): String {
        val stepLines = steps.mapIndexed { i, step ->
            "${i + 1}. [${step.status.name}] ${step.description}\n" +
                "   Result: ${step.result.ifBlank { "(none)" }}"
        }.joinToString("\n")
        return listOfNotNull(
            "Compose the final answer to the user's request from the step results below.",
            "Lead with the answer and keep it concise. If a step is FAILED, state plainly what that leaves unknown. Never invent data the step results don't contain.",
            "",
            "Request: $goal",
            "",
            "Steps:",
            stepLines,
            priorAnswer?.let { "\nYour previous answer:\n$it" },
            feedback?.let { "\nReviewer feedback — revise accordingly:\n$it" },
        ).joinToString("\n")
    }
}
