package com.poc.knowhowia

import kotlinx.serialization.json.Json
import java.io.File

class CaseStore(
    private val json: Json,
    private val casesDir: File = File("cases_min")
) {
    fun loadAllMinimalCases(): List<MinimalCase> {
        if (!casesDir.exists()) return emptyList()

        return casesDir
            .listFiles { f -> f.isFile && f.extension.lowercase() == "json" }
            ?.sortedBy { it.name }
            ?.map { file ->
                json.decodeFromString(MinimalCase.serializer(), file.readText())
            }
            ?: emptyList()
    }

    fun toKnownCasesContent(cases: List<MinimalCase>): String {
        return buildString {
            for (c in cases) {
                appendLine("CASE ID: ${c.caseId}")
                appendLine("Exception: ${c.crashSignature.exception}")
                appendLine("Top frames:")
                c.crashSignature.topFrames.forEach { appendLine("- $it") }
                appendLine("Solution pattern:")
                appendLine(c.solutionPattern)
                appendLine("PR:")
                appendLine("${c.relatedPr.url} | ${c.relatedPr.title}")
                appendLine("\n---\n")
            }
        }.trim()
    }
}
