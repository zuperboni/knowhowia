package com.poc.knowhowia

import io.ktor.client.HttpClient
import kotlinx.serialization.json.*
import java.io.File

class SimilarityRunner(
    private val json: Json,
    private val client: HttpClient,
    private val apiKey: String
) {
    private val promptTemplate = """
Você é um engenheiro Android experiente ajudando a identificar se um crash recém reportado
é similar a problemas já resolvidos anteriormente.

Sua tarefa é comparar um crash novo com uma lista de cases existentes da base KnowHowIA.

REGRAS IMPORTANTES:
- Avalie similaridade técnica, não apenas textual.
- Priorize exceção, top frames e contexto de lifecycle/timing.
- Considere similares mesmo em classes diferentes, se o mecanismo técnico for o mesmo.
- Se não houver similaridade relevante, deixe isso explícito.
- Não invente relações inexistentes.

RETORNE APENAS UM JSON VÁLIDO no formato abaixo:

{
  "similar_cases": [
    {
      "case_id": "",
      "similarity_reason": "",
      "related_pr": {
        "url": "",
        "title": ""
      }
    }
  ]
}

CRASH NOVO:
<<NEW_CRASH>>
{NEW_CRASH_CONTENT}
<<END_NEW_CRASH>>

CASES EXISTENTES:
<<KNOWN_CASES>>
{KNOWN_CASES_CONTENT}
<<END_KNOWN_CASES>>
""".trimIndent()

    fun buildSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        putJsonObject("properties") {
            putJsonObject("similar_cases") {
                put("type", "array")
                put("maxItems", 3)
                putJsonObject("items") {
                    put("type", "object")
                    put("additionalProperties", false)
                    putJsonObject("properties") {
                        putJsonObject("case_id") { put("type", "string") }
                        putJsonObject("similarity_reason") { put("type", "string") }
                        putJsonObject("related_pr") {
                            put("type", "object")
                            put("additionalProperties", false)
                            putJsonObject("properties") {
                                putJsonObject("url") { put("type", "string") }
                                putJsonObject("title") { put("type", "string") }
                            }
                            putJsonArray("required") {
                                add(JsonPrimitive("url"))
                                add(JsonPrimitive("title"))
                            }
                        }
                    }
                    putJsonArray("required") {
                        add(JsonPrimitive("case_id"))
                        add(JsonPrimitive("similarity_reason"))
                        add(JsonPrimitive("related_pr"))
                    }
                }
            }
        }
        putJsonArray("required") { add(JsonPrimitive("similar_cases")) }
    }

    suspend fun match(newCrashText: String, knownCasesText: String): SimilarCasesResult {
        val input = promptTemplate
            .replace("{NEW_CRASH_CONTENT}", newCrashText)
            .replace("{KNOWN_CASES_CONTENT}", knownCasesText)

        val request = ResponsesRequest(
            model = MODEL,
            instructions = "Compare similaridade e retorne apenas JSON válido conforme schema.",
            input = input,
            text = TextConfig(
                format = TextFormat(
                    type = "json_schema",
                    name = "knowhowia_similarity",
                    strict = true,
                    schema = buildSchema()
                )
            )
        )

        File("out").mkdirs()
        val raw = createResponseRaw(client, apiKey, request)
        File("out/response_raw_similarity.json").writeText(raw)

        checkForApiError(raw)

        val outputJsonText = extractJsonFromResponses(raw)
        File("out/output_text_similarity.json").writeText(outputJsonText)

        return json.decodeFromString(SimilarCasesResult.serializer(), outputJsonText)
    }
}
