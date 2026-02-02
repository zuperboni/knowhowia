package com.poc.knowhowia

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.File
import java.net.SocketTimeoutException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

const val MODEL = "gpt-4o-mini"

// ============================
// Data models (analyze output)
// ============================

@Serializable
data class CaseOutput(
    @SerialName("crash_signature") val crashSignature: CrashSignature,
    val hypothesis: String,
    @SerialName("solution_pattern") val solutionPattern: String,
    @SerialName("pr_evidence") val prEvidence: PrEvidence
)

@Serializable
data class CrashSignature(
    val exception: String,
    @SerialName("top_frames") val topFrames: List<String>
)

@Serializable
data class PrEvidence(
    @SerialName("files_touched") val filesTouched: List<String>,
    @SerialName("why_related") val whyRelated: String
)

@Serializable
data class ResponsesRequest(
    val model: String,
    val instructions: String,
    val input: String,
    val text: TextConfig
)

@Serializable
data class TextConfig(
    val format: TextFormat
)

@Serializable
data class TextFormat(
    val type: String,   // "json_schema"
    val name: String,   // REQUIRED
    val strict: Boolean,
    val schema: JsonObject
)
private val jsonConfig = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
    isLenient = true
    explicitNulls = false
    encodeDefaults = true
}
// ============================
// Prompts
// ============================

private val PROMPT_ANALYZE_V2 = """
Você é um engenheiro Android experiente analisando um crash real
e um Pull Request do GitHub que foi criado para corrigir esse problema.

Seu objetivo é gerar um registro técnico reutilizável para uma base
de conhecimento interna (KnowHowIA), ajudando outros desenvolvedores
a reconhecer e resolver problemas semelhantes no futuro.

IMPORTANTE — REGRAS DE QUALIDADE:
- Não trate conclusões como verdades absolutas.
- Use linguagem técnica e probabilística ("sugere", "provavelmente", "indica").
- Não invente informações que não estejam apoiadas no crash ou no PR.
- Se algo não puder ser inferido com segurança, deixe isso explícito.
- Priorize explicações de mecanismo técnico (lifecycle, timing, escopo, estado),
  não apenas a descrição da mudança feita no código.

FORMULE A HIPÓTESE COM RIGOR:
- Explique qual mecanismo técnico provavelmente causava o crash
  (ex: acesso ao Fragment antes do onAttach, escopo ativo fora do lifecycle,
  dependência criada no construtor, etc.).
- Deixe claro em que momento do ciclo de vida o erro tende a ocorrer.

DESCREVA O PADRÃO DE SOLUÇÃO:
- Foque no padrão técnico adotado e por que ele previne o crash.

EVIDÊNCIA DO PULL REQUEST:
- Liste arquivos relevantes.
- Explique a relação entre arquivos modificados e o local do crash (mesmo que indireta).

RETORNE APENAS UM JSON VÁLIDO no formato abaixo:

{
  "crash_signature": {
    "exception": "",
    "top_frames": []
  },
  "hypothesis": "",
  "solution_pattern": "",
  "pr_evidence": {
    "files_touched": [],
    "why_related": ""
  }
}

DADOS DO CRASH (Crashlytics):
<<CRASH>>
{CRASH_CONTENT}
<<END_CRASH>>

DADOS DO PULL REQUEST (GitHub):
<<PR>>
{PR_CONTENT}
<<END_PR>>
""".trimIndent()

private val PROMPT_MATCH = """
Você é um engenheiro Android experiente ajudando a identificar
se um crash recém reportado é similar a problemas já resolvidos anteriormente.

Sua tarefa é comparar um crash novo com uma lista de cases existentes
da base KnowHowIA e identificar quais são mais similares.

REGRAS IMPORTANTES:
- Avalie similaridade técnica, não textual.
- Priorize exceção, top frames e contexto de lifecycle/timing.
- Considere similares mesmo em classes diferentes, se o mecanismo técnico for o mesmo.
- Se não houver similaridade relevante, deixe isso explícito.
- Não invente relações inexistentes.
- Se múltiplos cases representarem o mesmo padrão técnico,
  retorne apenas o mais representativo e descarte os redundantes.


RETORNE APENAS UM JSON VÁLIDO no formato abaixo:

{
  "best_match": {
    "case_id": "",
    "similarity_reason": "",
    "related_pr": {
      "url": "",
      "title": ""
    }
  },
  "other_related": []
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

// ============================
// Main
// ============================

fun main(args: Array<String>) = runBlocking {
    val apiKey = System.getenv("OPENAI_API_KEY")
    if (apiKey.isNullOrBlank()) {
        System.err.println("ERRO: defina OPENAI_API_KEY no Run Configuration do IntelliJ (Environment variables).")
        exitProcess(1)
    }

    val mode = (args.firstOrNull() ?: "analyze").lowercase() // analyze | match
    val client = buildHttpClient()

    try {
        when (mode) {
            "analyze" -> runAnalyze(client, apiKey)
            "match" -> runMatch(client, apiKey)
            else -> {
                System.err.println("Modo inválido: $mode. Use: analyze ou match.")
                exitProcess(1)
            }
        }
    } finally {
        client.close()
    }
}

// ============================
// Analyze mode
// ============================

private suspend fun runAnalyze(client: HttpClient, apiKey: String) {
    val crashFile = File("crash.txt")
    val prFile = File("pr.txt")

    if (!crashFile.exists() || !prFile.exists()) {
        System.err.println("ERRO: coloque crash.txt e pr.txt no Working Directory.")
        System.err.println("Working directory atual: ${System.getProperty("user.dir")}")
        exitProcess(1)
    }

    val crashText = crashFile.readText()
    val prText = prFile.readText()

    val prompt = PROMPT_ANALYZE_V2
        .replace("{CRASH_CONTENT}", crashText)
        .replace("{PR_CONTENT}", prText)

    val request = ResponsesRequest(
        model = MODEL,
        instructions = "Retorne somente JSON válido conforme schema, sem texto extra.",
        input = prompt,
        text = TextConfig(
            format = TextFormat(
                type = "json_schema",
                name = "knowhowia_analyze_case",
                strict = true,
                schema = buildAnalyzeSchema()
            )
        )
    )

    File("out").mkdirs()
    val raw = retryWithBackoff {
        createResponseRaw(client, apiKey, request)
    }
    File("out/response_raw_analyze.json").writeText(raw)
    checkForApiError(raw)

    val outputJsonText = extractJsonFromResponses(raw)
    File("out/output_text_analyze.json").writeText(outputJsonText)

    val case = jsonConfig.decodeFromString(CaseOutput.serializer(), outputJsonText)
    val prettyCase = jsonConfig.encodeToString(CaseOutput.serializer(), case)

    println("=== CASE (FULL) ===")
    println(prettyCase)
    File("out/case.json").writeText(prettyCase)

    // Versionamento local
    val caseId = generateCaseId()
    File("cases").mkdirs()
    File("cases/case-$caseId.json").writeText(prettyCase)

    // Deriva o case mínimo (pra busca/chat)
    val relatedPr = extractPrLinkAndTitle(prText)
    val minimal = toMinimalCase(caseId, case, relatedPr)
    val prettyMin = jsonConfig.encodeToString(MinimalCase.serializer(), minimal)

    File("cases_min").mkdirs()
    File("cases_min/case-$caseId.json").writeText(prettyMin)

    println("\nSalvos:")
    println("- out/case.json")
    println("- cases/case-$caseId.json")
    println("- cases_min/case-$caseId.json")
}

// ============================
// Match mode
// ============================

private suspend fun runMatch(client: HttpClient, apiKey: String) {
    val crashFile = File("crash.txt")
    if (!crashFile.exists()) {
        System.err.println("ERRO: coloque crash.txt no Working Directory.")
        System.err.println("Working directory atual: ${System.getProperty("user.dir")}")
        exitProcess(1)
    }

    val casesDir = File("cases_min")
    if (!casesDir.exists()) {
        System.err.println("ERRO: diretório ./cases_min não existe. Rode 'analyze' pelo menos 1 vez para gerar cases mínimos.")
        exitProcess(1)
    }

    val minimalCases = casesDir
        .listFiles { f -> f.isFile && f.extension.lowercase() == "json" }
        ?.sortedBy { it.name }
        ?.map { file -> jsonConfig.decodeFromString(MinimalCase.serializer(), file.readText()) }
        ?: emptyList()

    if (minimalCases.isEmpty()) {
        System.err.println("ERRO: nenhum arquivo .json encontrado em ./cases_min. Rode 'analyze' para gerar cases.")
        exitProcess(1)
    }

    val knownCasesContent = buildKnownCasesContent(minimalCases)
    val newCrashText = crashFile.readText()

    val prompt = PROMPT_MATCH
        .replace("{NEW_CRASH_CONTENT}", newCrashText)
        .replace("{KNOWN_CASES_CONTENT}", knownCasesContent)

    val request = ResponsesRequest(
        model = MODEL,
        instructions = "Compare e retorne somente JSON válido conforme schema, sem texto extra.",
        input = prompt,
        text = TextConfig(
            format = TextFormat(
                type = "json_schema",
                name = "knowhowia_match_similar",
                strict = true,
                schema = buildMatchSchema()
            )
        )
    )

    File("out").mkdirs()

    val raw = retryWithBackoff {
        createResponseRaw(client, apiKey, request)
    }
    File("out/response_raw_match.json").writeText(raw)
    checkForApiError(raw)

    val outputJsonText = extractJsonFromResponses(raw)
    File("out/output_text_match.json").writeText(outputJsonText)

    val result = jsonConfig.decodeFromString(SimilarCasesResult.serializer(), outputJsonText)
    val pretty = jsonConfig.encodeToString(SimilarCasesResult.serializer(), result)

    println("=== SIMILAR CASES ===")
    val chatMessage = formatForChat(result)
    println(chatMessage)

    File("out/similar_cases.json").writeText(pretty)
    println("\nSalvo em: out/similar_cases.json")
}

// ============================
// Helpers: minimal case derivation
// ============================

private fun toMinimalCase(caseId: String, case: CaseOutput, pr: RelatedPr): MinimalCase {
    // problem_summary: aqui você pode ser mais sofisticada depois.
    // Por enquanto: usamos a hipótese (curta) como resumo do problema.
    val summary = case.hypothesis.trim().take(280)

    return MinimalCase(
        caseId = "case-$caseId",
        crashSignature = case.crashSignature,
        problemSummary = summary,
        solutionPattern = case.solutionPattern.trim(),
        relatedPr = pr
    )
}

private fun formatForChat(result: SimilarCasesResult): String {
    if (result.similarCases.isEmpty()) {
        return "🤷 Não encontrei nenhum caso semelhante na base ainda."
    }

    val best = result.similarCases.first()

    val pattern = best.similarityReason

    return buildString {
        appendLine("🔎 **Já vimos esse crash antes**")
        appendLine()
        appendLine("– Padrão: $pattern")
        appendLine("– Motivo: ${best.similarityReason.trim()}")
        appendLine("– PR: ${best.relatedPr.url}")
    }
}

private fun extractPrLinkAndTitle(prText: String): RelatedPr {
    // pega a primeira URL de PR do GitHub (se existir)
    val urlRegex = Regex("""https?://github\.com/[^\s]+/pull/\d+""", RegexOption.IGNORE_CASE)
    val url = urlRegex.find(prText)?.value ?: "unknown"

    // tenta pegar "Title:" ou primeira linha não vazia como título
    val title = prText.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.startsWith("Title:", ignoreCase = true) }
        ?.removePrefix("Title:")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: prText.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() }?.take(120)
        ?: "unknown"

    return RelatedPr(url = url, title = title)
}

private fun buildKnownCasesContent(cases: List<MinimalCase>): String {
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

private fun generateCaseId(): String {
    val dt = LocalDateTime.now()
    val fmt = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    return dt.format(fmt)
}

// ============================
// Helpers: schemas
// ============================

private fun buildAnalyzeSchema(): JsonObject = buildJsonObject {
    put("type", "object")
    put("additionalProperties", false)

    putJsonObject("properties") {
        putJsonObject("crash_signature") {
            put("type", "object")
            put("additionalProperties", false)
            putJsonObject("properties") {
                putJsonObject("exception") { put("type", "string") }
                putJsonObject("top_frames") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                    put("maxItems", 8)
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("exception"))
                add(JsonPrimitive("top_frames"))
            }
        }

        putJsonObject("hypothesis") { put("type", "string") }
        putJsonObject("solution_pattern") { put("type", "string") }

        putJsonObject("pr_evidence") {
            put("type", "object")
            put("additionalProperties", false)
            putJsonObject("properties") {
                putJsonObject("files_touched") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                    put("maxItems", 12)
                }
                putJsonObject("why_related") { put("type", "string") }
            }
            putJsonArray("required") {
                add(JsonPrimitive("files_touched"))
                add(JsonPrimitive("why_related"))
            }
        }
    }

    putJsonArray("required") {
        add(JsonPrimitive("crash_signature"))
        add(JsonPrimitive("hypothesis"))
        add(JsonPrimitive("solution_pattern"))
        add(JsonPrimitive("pr_evidence"))
    }
}

private fun buildMatchSchema(): JsonObject = buildJsonObject {
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

// ============================
// Helpers: API call + parsing
// ============================

suspend fun createResponseRaw(
    client: HttpClient,
    apiKey: String,
    request: ResponsesRequest
): String {
    val res = client.post("https://api.openai.com/v1/responses") {
        header("Authorization", "Bearer $apiKey")
        contentType(ContentType.Application.Json)
        setBody(request)
    }
    return res.body()
}

fun checkForApiError(raw: String) {
    val root = Json.parseToJsonElement(raw).jsonObject
    val errEl = root["error"] ?: return
    if (errEl is JsonNull) return

    val errObj = errEl.jsonObject
    val message = errObj["message"]?.jsonPrimitive?.content ?: "unknown error"
    val code = errObj["code"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull
    val type = errObj["type"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull

    throw IllegalStateException("API error${code?.let { " ($it)" } ?: ""}${type?.let { " [$it]" } ?: ""}: $message")
}

fun extractJsonFromResponses(raw: String): String {
    val root = Json.parseToJsonElement(raw).jsonObject

    root["output_text"]?.let { el ->
        val s = el.jsonPrimitive.content
        if (s.isNotBlank()) return s.trim()
    }

    val outputArr = root["output"]?.jsonArray
        ?: error("Resposta não contém 'output' nem 'output_text'. Veja out/response_raw_*.json.")

    val texts = mutableListOf<String>()

    for (item in outputArr) {
        val itemObj = item.jsonObject
        val contentArr = itemObj["content"]?.jsonArray ?: continue

        for (content in contentArr) {
            val cObj = content.jsonObject
            val text = cObj["text"]?.jsonPrimitive?.content
            if (!text.isNullOrBlank()) texts.add(text)
        }
    }

    if (texts.isEmpty()) {
        error("Não encontrei texto em output[].content[].text. Veja out/response_raw_*.json.")
    }

    return texts.joinToString("\n").trim()
}

private fun buildHttpClient(): HttpClient =
    HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(jsonConfig)
        }
        install(HttpTimeout) {
            // request total (inclui conexão + servidor processar + resposta)
            requestTimeoutMillis = 240_000  // 4 min
            // tempo pra conectar
            connectTimeoutMillis = 30_000   // 30s
            // tempo esperando dados do servidor (onde seu erro estoura)
            socketTimeoutMillis = 240_000   // 4 min
        }

        engine {
            config {
                retryOnConnectionFailure(true)
                connectTimeout(30, TimeUnit.SECONDS)
                readTimeout(240, TimeUnit.SECONDS)
                writeTimeout(240, TimeUnit.SECONDS)
            }
        }
    }

private suspend fun <T> retryWithBackoff(
    maxAttempts: Int = 4,
    initialDelayMs: Long = 1_000,
    factor: Double = 2.0,
    block: suspend () -> T
): T {
    var delayMs = initialDelayMs
    var last: Throwable? = null

    repeat(maxAttempts) { attempt ->
        try {
            return block()
        } catch (e: SocketTimeoutException) {
            last = e
        } catch (e: Exception) {
            // opcional: você pode filtrar aqui só erros de rede/5xx
            last = e
        }

        if (attempt < maxAttempts - 1) {
            delay(delayMs)
            delayMs = (delayMs * factor).toLong()
        }
    }
    throw last ?: IllegalStateException("retry failed")
}
