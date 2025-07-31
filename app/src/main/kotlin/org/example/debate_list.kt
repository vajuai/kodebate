package org.example

import ai.koog.agents.core.agent.AIAgent
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient
import ai.koog.prompt.executor.clients.openrouter.OpenRouterModels
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import kotlinx.coroutines.runBlocking

/**
 * 辩论状态管理
 */
data class DebateState(
    val topic: String,
    val totalRounds: Int,
    val currentRound: Int = 1
) {
    fun shouldContinueDebate(): Boolean = currentRound <= totalRounds
    fun nextRound(): DebateState = copy(currentRound = currentRound + 1)
}

/**
 * 内存和历史管理器
 */
class DebateHistoryManager(
    private val executor: PromptExecutor,
    private val topic: String,
    private val summaryModel: LLModel = OpenRouterModels.Claude3Haiku
) {
    private val fullHistory = mutableListOf<String>()
    private var summarizedHistory = "无历史记录"

    fun addTurn(speaker: String, round: Int, speech: String) {
        val turnRecord = "第 $round 轮, $speaker: $speech"
        fullHistory.add(turnRecord)
    }

    suspend fun updateSummary() {
        println("📝 [HistoryManager] 使用 ${summaryModel.id} 模型生成新一轮的摘要...")
        val historyToSummarize = getFullHistoryForJudge()
        val summarizerAgent = AIAgent(
            executor = executor,
            llmModel = summaryModel,
            systemPrompt = """
                你是一个高效的辩论摘要员。请根据以下完整辩论记录，生成一个简洁、中立的摘要。
                这个摘要将作为下一轮辩论的上下文。请聚焦核心论点和交锋，删除冗余信息。
                辩论主题是: "$topic"。
            """.trimIndent()
        )
        summarizedHistory = summarizerAgent.run(historyToSummarize)
        println("✅ [HistoryManager] 摘要已更新。")
    }

    fun getContextForNextTurn(): String = summarizedHistory
    fun getFullHistoryForJudge(): String = fullHistory.joinToString("\n\n")
}

fun parseDebateInput(userInput: String): DebateState {
    val parts = userInput.split(',', '，')
    val topic = parts.getOrNull(0)?.trim() ?: "人工智能的利与弊"
    val rounds = parts.getOrNull(1)?.filter { it.isDigit() }?.toIntOrNull() ?: 3
    return DebateState(topic = topic, totalRounds = rounds)
}

// --- Grok 模型和 Provider 定义 ---
object GrokProvider : LLMProvider("grok","grok")
val grok1Model = LLModel(
    id = "grok-4",
    provider = GrokProvider,
    capabilities = listOf(LLMCapability.Tools, LLMCapability.Completion)
)

// --- DeepSeek 模型和 Provider 定义 ---
object DeepSeekProvider : LLMProvider("deepseek","deepseek")
val deepseekJudgeModel = LLModel(
    id = "deepseek-chat",
    provider = DeepSeekProvider,
    capabilities = listOf(LLMCapability.Tools, LLMCapability.Completion)
)

fun createDebaterAgent(
    role: String,
    topic: String,
    modelName: String,
    executor: PromptExecutor
): AIAgent<String, String> {
    val model = when (modelName.lowercase()) {
        "gpt-4o" -> OpenAIModels.Chat.GPT4o
        "grok" -> grok1Model
        else -> throw IllegalArgumentException("未知的辩手模型: $modelName")
    }

    val systemPrompt = """
        你是一位顶级的AI辩手。你的角色是 "$role"。
        辩论主题是"$topic"，你需要提出强有力的、有逻辑的论点来支持你的立场。
        - **保持角色**: 你的所有发言都必须严格围绕你的立场展开。
        - **结构清晰**: 你的发言应该有明确的结构 (例如：先重申我方观点，然后反驳对方，最后提出新论据)。
        - **有力反驳**: 仔细分析对手的论点，并提出直接、有力的反驳。
        - **保持专注**: 只输出你的辩论发言，不要包含任何额外的解释或对话。
        - **全部使用中文**
    """.trimIndent()

    return AIAgent(
        executor = executor,
        llmModel = model,
        systemPrompt = systemPrompt
    )
}

fun createJudgeAgent(executor: PromptExecutor): AIAgent<String, String> {
    val systemPrompt = """
        你是一位经验丰富、立场中立的辩论赛裁判。
        你的任务是根据双方完整的、未经压缩的辩论记录，做出最终的、决定性的评判。
        你的评判必须公平、逻辑严谨且结构清晰，需要包括：
        1. 对双方核心矛盾和主要论点的简要回顾。
        2. 对每一方论证质量、逻辑连贯性、证据有效性和反驳力度的分析。
        3. 明确宣布胜利方，并提供令人信服的详细理由来支持你的裁决。
        **你的全部输出都必须是中文。**
        **你必须做出明确的裁决，不能判为平局。**
    """.trimIndent()

    return AIAgent(
        executor = executor,
        llmModel = deepseekJudgeModel, // [替换] 使用 DeepSeek 作为裁判模型
        systemPrompt = systemPrompt
    )
}

fun main() {
    System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn")

    println("🎯 动态 AI 辩论系统 (v3.0 DeepSeek 裁判版) 启动...")

    val openAIApiToken = System.getenv("OPENAI_API_KEY")
    val grokApiToken = System.getenv("GROK_API_KEY")
    val deepseekApiToken = System.getenv("DEEPSEEK_API_KEY") // [新增] DeepSeek API Key
    val openRouterApiToken = System.getenv("OPENROUTER_API_KEY")

    if (openAIApiToken.isNullOrBlank() || grokApiToken.isNullOrBlank() || openRouterApiToken.isNullOrBlank() || deepseekApiToken.isNullOrBlank()) {
        println("❌ 错误: 请确保 OPENAI_API_KEY, GROK_API_KEY, DEEPSEEK_API_KEY, 和 OPENROUTER_API_KEY 环境变量均已设置。")
        return
    }

    // [新增] DeepSeek 客户端
    val deepseekClient = OpenAILLMClient(
        apiKey = deepseekApiToken,
        settings = OpenAIClientSettings("https://api.deepseek.com")
    )

    val grokClient = OpenAILLMClient(
        apiKey = grokApiToken,
        settings = OpenAIClientSettings("https://api.x.ai")
    )

    val executor = MultiLLMPromptExecutor(
        LLMProvider.OpenAI to OpenAILLMClient(openAIApiToken),
        GrokProvider to grokClient,
        DeepSeekProvider to deepseekClient, // [新增] 添加 DeepSeek 客户端
        LLMProvider.OpenRouter to OpenRouterLLMClient(openRouterApiToken) // 用于摘要
    )

    println("💬 请输入辩论主题和轮次 (例如: 远程工作对公司文化的利弊,3轮):")
    print("🎤 请输入: ")
    val userInput = readlnOrNull()?.takeIf { it.isNotBlank() } ?: "远程工作对公司文化的利弊,3轮"
    var state = parseDebateInput(userInput)
    println("\n辩论开始！主题：'${state.topic}'，总共 ${state.totalRounds} 轮\n")

    // [修改] 创建 Agent 时传入辩论主题
    val proAgent = createDebaterAgent("正方", state.topic, "gpt-4o", executor)
    val conAgent = createDebaterAgent("反方", state.topic, "grok", executor)
    val judgeAgent = createJudgeAgent(executor)

    val historyManager = DebateHistoryManager(
        executor = executor,
        topic = state.topic,
        summaryModel = OpenRouterModels.Claude3Haiku
    )

    runBlocking {
        while (state.shouldContinueDebate()) {
            println("--- 第 ${state.currentRound} 轮 ---")

            val proInput = "辩论上下文:\n${historyManager.getContextForNextTurn()}\n\n请根据以上信息，开始你的发言。"
            val proSpeech = proAgent.run(proInput)
            println("🗣️ 正方 (GPT-4o) 发言:\n$proSpeech\n")
            historyManager.addTurn("正方", state.currentRound, proSpeech)

            val conInput = "辩论上下文:\n${historyManager.getContextForNextTurn()}\n\n请根据以上信息，开始你的发言。"
            val conSpeech = conAgent.run(conInput)
            println("🗣️ 反方 (Grok/${grok1Model.id}) 发言:\n$conSpeech\n")
            historyManager.addTurn("反方", state.currentRound, conSpeech)

            historyManager.updateSummary()

            state = state.nextRound()
        }

        println("\n--- 辩论结束，进入最终评判环节 ---")

        val finalHistory = historyManager.getFullHistoryForJudge()
        val finalJudgment = judgeAgent.run("这是完整的辩论历史记录:\n\n$finalHistory")

        // [替换] 更新裁判的打印信息
        println("⚖️ 裁判 (DeepSeek/${deepseekJudgeModel.id}) 最终判决:\n$finalJudgment")
        println("\n🏆 AI vs AI 辩论大赛圆满结束！")
    }
}