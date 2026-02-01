package com.poc.knowhowia

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SimilarCasesResult(
    @SerialName("similar_cases") val similarCases: List<SimilarCaseHit>
)

@Serializable
data class SimilarCaseHit(
    @SerialName("case_id") val caseId: String,
    @SerialName("similarity_reason") val similarityReason: String,
    @SerialName("related_pr") val relatedPr: RelatedPr
)
