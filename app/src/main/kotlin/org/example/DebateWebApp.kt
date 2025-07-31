package org.example

import ai.koog.agents.memory.providers.LocalFileMemoryProvider
import ai.koog.agents.memory.providers.LocalMemoryConfig
import ai.koog.agents.memory.storage.Aes256GCMEncryptor
import ai.koog.agents.memory.storage.EncryptedStorage
import ai.koog.rag.base.files.JVMFileSystemProvider
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.Path

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module).start(wait = true)
}

@Serializable
data class TurnData(val side: String, val model: String, val speech: String)

fun Application.module() {
    install(ContentNegotiation) {
        json()
    }
    install(SSE)

    routing {
        get("/") {
            call.respondText(
                this::class.java.classLoader.getResource("index.html")!!.readText(),
                ContentType.Text.Html
            )
        }
        get("/static/{fileName}") {
            val fileName = call.parameters["fileName"]!!
            val resource = this::class.java.classLoader.getResource(fileName)
            if (resource != null) {
                val contentType = when {
                    fileName.endsWith(".css") -> ContentType.Text.CSS
                    fileName.endsWith(".js") -> ContentType.Application.JavaScript
                    else -> ContentType.Text.Plain
                }
                call.respondText(resource.readText(), contentType)
            } else {
                call.respond(HttpStatusCode.NotFound)
            }
        }

        sse("/debate") {
            val rounds = call.request.queryParameters["rounds"]?.toIntOrNull() ?: 3
            try {
                val turnCounter = AtomicInteger(0)
                val summaryCounter = AtomicInteger(0)
                val json = Json

                suspend fun sendEvent(event: String, data: String) {
                    send(ServerSentEvent(data, event))
                }

                // Helper function to identify verdict-like text from the agent
                fun isVerdict(text: String): Boolean {
                    val keywords = listOf("最终获胜方", "判决详情", "裁判", "评委", "总结本次辩论", "宣布胜利方")
                    return keywords.any { text.contains(it, ignoreCase = true) }
                }

                // FIX 1: Make the script tool robust against incorrect calls from the agent
                val webScriptToolSet = object : ScriptToolSet() {
                    override fun recordDebateScript(scriptContent: String): String {
                        // WORKAROUND: If the agent mistakenly calls this tool with the final verdict,
                        // we intercept it and emit the proper 'verdict' event.
                        if (isVerdict(scriptContent)) {
                            runBlocking {
                                sendEvent("round_separator", "最终裁决")
                                // Format the raw text to look good in the verdict box.
                                val formattedVerdict = "#### 裁判文书\n\n${scriptContent.replace("\n", "\n\n")}"
                                sendEvent("verdict", formattedVerdict)
                            }
                            return "最终裁决已在网页上公布。"
                        }

                        // Original logic for regular debate turns
                        val currentTurn = turnCounter.getAndIncrement()
                        val side = if (currentTurn % 2 == 0) "正方" else "反方"
                        val model = if (currentTurn % 2 == 0) "Gemini" else "GPT"
                        val round = (currentTurn / 2) + 1

                        runBlocking {
                            if (side == "正方" && round <= rounds) {
                                sendEvent("round_separator", "第 $round 轮辩论")
                            }
                            val turnData = TurnData(side, model, scriptContent)
                            sendEvent("message", json.encodeToString(turnData))
                        }
                        return "发言已记录并成功在网页上显示。"
                    }
                }

                // FIX 2: Ensure summaries are displayed in a distinct format
                val webOpinionToolSet = object : OpinionToolSet() {
                    override fun analyzeDebateOpinions(opinionsData: String): String {
                        val summaryNum = summaryCounter.getAndIncrement()
                        val side = if (summaryNum == 0) "正方" else "反方"

                        runBlocking {
                            if (summaryNum == 0) {
                                sendEvent("round_separator", "总结陈词")
                            }
                            // Re-use the 'verdict' event type to get a distinct visual box.
                            val formattedSummary = "#### $side 总结\n\n${opinionsData.replace("\n", "\n\n")}"
                            sendEvent("verdict", formattedSummary)
                        }
                        return "总结陈词已记录并显示在网页上。"
                    }
                }

                val webHostToolSet = object : DebateHostToolSet() {
                    override fun judgeDebateWinner(winner: String, judgmentDetails: String): String {
                        val formattedVerdict = """
                        |### 最终获胜方: **$winner**
                        |
                        |#### 判决详情:
                        |${judgmentDetails.replace("\n", "\n| ")}
                        """.trimMargin()
                        runBlocking {
                            sendEvent("round_separator", "最终裁决")
                            sendEvent("verdict", formattedVerdict)
                        }
                        return "最终裁决已在网页上公布。"
                    }

                    override fun analyzeDebateHighlights(highlightsAnalysis: String): String {
                        println("WebServer: 亮眼表现分析完成 (未在UI上显示)。")
                        return super.analyzeDebateHighlights(highlightsAnalysis)
                    }
                }

                val webDebaterToolSet = object : DebaterToolSet() {
                    override fun analyzeDebaterStrategy(strategyInfo: String): String {
                        println("WebServer: 辩论策略分析完成 (未在UI上显示)。")
                        return super.analyzeDebaterStrategy(strategyInfo)
                    }
                }

                val topic = call.request.queryParameters["topic"] ?: "科技发展利大于弊"
                val secretKey = "7UL8fsTqQDq9siUZgYO3bLGqwMGXQL4vKMWMscKB7Cw="

                val memoryProvider = LocalFileMemoryProvider(
                    config = LocalMemoryConfig("debate-memory-web"),
                    storage = EncryptedStorage(JVMFileSystemProvider.ReadWrite, Aes256GCMEncryptor(secretKey)),
                    fs = JVMFileSystemProvider.ReadWrite,
                    root = Path("./debate-memory-web/"),
                )

                val agent = createDebateHostAgent(
                    debaterToolSet = webDebaterToolSet,
                    opinionToolSet = webOpinionToolSet,
                    scriptToolSet = webScriptToolSet,
                    hostToolSet = webHostToolSet,
                    memoryProvider = memoryProvider,
                    maxAgentIterations = 300,
                    defaultRound = rounds
                )

                agent.run("辩论主题: $topic")

                send(ServerSentEvent(data = "Debate finished", event = "finish"))

            } catch (e: Exception) {
                e.printStackTrace()
                send(ServerSentEvent(data = "Error: ${e.message}", event = "finish"))
            }
        }
    }
}
