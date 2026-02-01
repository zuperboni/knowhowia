package com.poc.knowhowia

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MinimalCase(
    @SerialName("case_id") val caseId: String,
    @SerialName("crash_signature") val crashSignature: CrashSignature,
    @SerialName("problem_summary") val problemSummary: String,
    @SerialName("solution_pattern") val solutionPattern: String,
    @SerialName("related_pr") val relatedPr: RelatedPr
)
@Serializable
data class RelatedPr(
    val url: String,
    val title: String
)
