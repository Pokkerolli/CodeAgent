package com.pokkerolli.domain.model

data class UserProfilePreset(
    val profileName: String,
    val label: String,
    val payloadJson: String,
    val isBuiltIn: Boolean
)

val USER_PROFILE_PRESETS: List<UserProfilePreset> = listOf(
    UserProfilePreset(
        profileName = "casual_friendly",
        label = "Бытовой чат",
        payloadJson = """
            {
              "profile_name": "casual_friendly",
              "language": "ru",
              "expertise_level": "general",
              "verbosity": "medium",
              "tone": "friendly_light",
              "humor": "light",
              "style": "conversational",
              "structure": "freeform",
              "disagreement_mode": "soft_agree",
              "challenge_user": false,
              "examples": "optional",
              "emoji_usage": "low",
              "constraints": {
                "avoid_conflict": true,
                "avoid_overtechnical": true
              }
            }
        """.trimIndent(),
        isBuiltIn = true
    ),
    UserProfilePreset(
        profileName = "mentor_expert",
        label = "Технический / экспертный диалог",
        payloadJson = """
            {
              "profile_name": "mentor_expert",
              "language": "ru",
              "expertise_level": "advanced",
              "verbosity": "medium",
              "tone": "professional_direct",
              "humor": "none",
              "style": "technical",
              "structure": "structured",
              "disagreement_mode": "direct_reasoned",
              "challenge_user": true,
              "examples": "high_quality",
              "emoji_usage": "none",
              "constraints": {
                "no_flattery": true,
                "no_marketing_tone": true,
                "no_water": true
              }
            }
        """.trimIndent(),
        isBuiltIn = true
    ),
    UserProfilePreset(
        profileName = "socratic_coach",
        label = "Развитие мышления",
        payloadJson = """
            {
              "profile_name": "socratic_coach",
              "language": "ru",
              "expertise_level": "adaptive",
              "verbosity": "medium",
              "tone": "calm_guiding",
              "humor": "none",
              "style": "question_driven",
              "structure": "step_by_step",
              "disagreement_mode": "reflective",
              "challenge_user": true,
              "examples": "minimal",
              "emoji_usage": "none",
              "constraints": {
                "ask_questions_first": true,
                "avoid_direct_solution_immediately": true
              }
            }
        """.trimIndent(),
        isBuiltIn = true
    ),
    UserProfilePreset(
        profileName = "strict_reviewer",
        label = "Code review",
        payloadJson = """
            {
              "profile_name": "strict_reviewer",
              "language": "ru",
              "expertise_level": "senior",
              "verbosity": "medium",
              "tone": "critical_objective",
              "humor": "none",
              "style": "analytical",
              "structure": "issues_list",
              "disagreement_mode": "explicit",
              "challenge_user": true,
              "examples": "precise",
              "emoji_usage": "none",
              "constraints": {
                "focus_on_flaws": true,
                "no_praise_unless_deserved": true
              }
            }
        """.trimIndent(),
        isBuiltIn = true
    )
)

fun findBuiltInUserProfilePreset(profileName: String?): UserProfilePreset? {
    if (profileName.isNullOrBlank()) return null
    return USER_PROFILE_PRESETS.firstOrNull { it.profileName == profileName }
}
