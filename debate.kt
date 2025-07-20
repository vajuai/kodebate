package org.example

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.entity.ToolSelectionStrategy
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.reflect.asTools
import ai.koog.agents.ext.agent.ProvideStringSubgraphResult
import ai.koog.agents.ext.agent.StringSubgraphResult
import ai.koog.agents.ext.agent.subgraphWithTask
import ai.koog.agents.ext.tool.SayToUser
import ai.koog.agents.memory.config.MemoryScopeType
import ai.koog.agents.memory.feature.AgentMemory
import ai.koog.agents.memory.feature.nodes.nodeLoadFromMemory
import ai.koog.agents.memory.feature.nodes.nodeSaveToMemory
import ai.koog.agents.memory.model.Concept
import ai.koog.agents.memory.model.FactType
import ai.koog.agents.memory.model.MemorySubject
import ai.koog.agents.memory.providers.AgentMemoryProvider
import ai.koog.agents.memory.providers.LocalFileMemoryProvider
import ai.koog.agents.memory.providers.LocalMemoryConfig
import ai.koog.agents.memory.storage.Aes256GCMEncryptor
import ai.koog.agents.memory.storage.EncryptedStorage
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLMProvider
import ai.koog.rag.base.files.JVMFileSystemProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlin.io.path.Path
import ai.koog.prompt.executor.clients.openrouter.OpenRouterModels

private object MemorySubjects {
    @Serializable
    data object Debater : MemorySubject() {
        override val name: String = "debater"
        override val promptDescription: String = "辩方信息 (正方辩方及其LLM配置，辩论角色信息等)"
        override val priorityLevel: Int = 1
    }

    @Serializable
    data object Opinion : MemorySubject() {
        override val name: String = "opinion"
        override val promptDescription: String = "论点信息 (辩论核心论点，主要观点，支撑理由等)"
        override val priorityLevel: Int = 2
    }

    @Serializable
    data object Script : MemorySubject() {
        override val name: String = "script"
        override val promptDescription: String = "辩词信息 (正反方各轮的具体辩词，发言内容等)"
        override val priorityLevel: Int = 3
    }
}

/**
 * 创建辩论主持人代理
 */
fun createDebateHostAgent(
    debaterToolSet: ToolSet,
    opinionToolSet: ToolSet,
    scriptToolSet: ToolSet,
    hostToolSet: ToolSet,  // 新增主持人工具集参数
    memoryProvider: AgentMemoryProvider,
    maxAgentIterations: Int = 50,
    featureName: String? = null,
    productName: String? = null,
    organizationName: String? = null,
): AIAgent<String, String> {
    // 获取API密钥
    val openAIApiToken = System.getenv("OPENAI_API_KEY") ?: error("OPENAI_API_KEY environment variable not set")
    val openRouterApiToken = System.getenv("OPENROUTER_API_KEY") ?: error("OPENROUTER_API_KEY environment variable not set")
    val googleApiToken = System.getenv("GEMINI_API_KEY") ?: error("GEMINI_API_KEY environment variable not set")

    // 创建各种LLM客户端
    val openAIClient = OpenAILLMClient(openAIApiToken)
    val openRouterClient = OpenRouterLLMClient(openRouterApiToken)
    val googleClient = GoogleLLMClient(googleApiToken)

    // 创建多LLM执行器
    val multiExecutor = MultiLLMPromptExecutor(
        LLMProvider.OpenAI to openAIClient,
        LLMProvider.OpenRouter to openRouterClient,
        LLMProvider.Google to googleClient
    )

    // Memory concepts
    val debaterConcept = Concept(
        keyword = "debater-info",
        description = "辩方信息包括正反方身份和角色定义、各方使用的LLM模型配置、辩论分工和策略偏好",
        factType = FactType.MULTIPLE
    )

    val opinionConcept = Concept(
        keyword = "debate-opinions",
        description = "辩论论点信息包括辩论的核心议题和主题、正反方的主要论点和观点、双方论点的支撑理由",
        factType = FactType.MULTIPLE
    )

    val scriptConcept = Concept(
        keyword = "debate-scripts",
        description = "辩词信息包括各轮辩论的具体发言内容、正反方各轮的辩词和回应、发言的时间和轮次记录",
        factType = FactType.MULTIPLE
    )

    // Agent configuration - 主持人使用OpenAI GPT-4o模型
    val agentConfig = AIAgentConfig(
        prompt = prompt("debate-host") {},
        model = OpenAIModels.Chat.GPT4o,
        maxAgentIterations = maxAgentIterations
    )

    // Create agent strategy
    val strategy = strategy<String, String>("debate-session", toolSelectionStrategy = ToolSelectionStrategy.NONE) {
        val loadMemory by subgraph<String, String>(tools = emptyList()) {
            val nodeLoadDebater by nodeLoadFromMemory<String>(
                concept = debaterConcept,
                subject = MemorySubjects.Debater,
                scope = MemoryScopeType.PRODUCT
            )

            val nodeLoadOpinion by nodeLoadFromMemory<String>(
                concept = opinionConcept,
                subject = MemorySubjects.Opinion,
                scope = MemoryScopeType.PRODUCT
            )

            val nodeLoadScript by nodeLoadFromMemory<String>(
                concept = scriptConcept,
                subject = MemorySubjects.Script,
                scope = MemoryScopeType.PRODUCT
            )

            nodeStart then nodeLoadDebater then nodeLoadOpinion then nodeLoadScript then nodeFinish
        }

        // 正方辩论 - 使用OpenAI GPT-4o，简化提示
        val proDebateSession by subgraphWithTask<String>(
            tools = scriptToolSet.asTools() + listOf(SayToUser),
            llmModel = OpenAIModels.Chat.GPT4o
        ) { userInput ->
            """
            🟢 你是正方辩手 (OpenAI GPT-4o)
            
            辩论主题: $userInput
            
            你的任务:
            1. 支持这个观点并提出强有力的论据
            2. 必须调用 recordDebateScript 工具记录你的发言
            3. 必须调用 SayToUser 工具输出你的观点
            
            请立即开始你的辩论发言！
            """.trimIndent()
        }

        val retrieveProResult by node<StringSubgraphResult, String> { 
            val result = it.result
            println("🟢 正方 (OpenAI GPT-4o) 发言完成")
            result
        }

        // 保存正方发言
        val saveProToMemory by subgraph<String, String>(tools = emptyList()) {
            val saveScript by nodeSaveToMemory<String>(
                concept = scriptConcept,
                subject = MemorySubjects.Script,
                scope = MemoryScopeType.PRODUCT
            )
            nodeStart then saveScript then nodeFinish
        }

        // 反方辩论 - 使用Google Gemini，简化提示
        val conDebateSession by subgraphWithTask<String>(
            tools = scriptToolSet.asTools() + listOf(SayToUser),
            llmModel = GoogleModels.Gemini2_0Flash  // 改用更稳定的模型
        ) { userInput ->
            """
            🔴 你是反方辩手 (Google Gemini)
            
            辩论主题: $userInput
            
            你的任务:
            1. 反对这个观点并提出有力的反驳
            2. 必须调用 recordDebateScript 工具记录你的发言  
            3. 必须调用 SayToUser 工具输出你的观点
            
            请立即开始你的反驳发言！
            """.trimIndent()
        }

        val retrieveConResult by node<StringSubgraphResult, String> { 
            val result = it.result
            println("🔴 反方 (Google Gemini) 发言完成")
            result
        }

        // 保存反方发言
        val saveConToMemory by subgraph<String, String>(tools = emptyList()) {
            val saveScript by nodeSaveToMemory<String>(
                concept = scriptConcept,
                subject = MemorySubjects.Script,
                scope = MemoryScopeType.PRODUCT
            )
            nodeStart then saveScript then nodeFinish
        }

        // 第二轮：正方再反驳
        val proRound2 by subgraphWithTask<String>(
            tools = scriptToolSet.asTools() + listOf(SayToUser),
            llmModel = OpenAIModels.Chat.GPT4o
        ) { userInput ->
            """
            🟢 你是正方辩手第二轮 (OpenAI GPT-4o)
            
            基于反方的质疑，请进一步加强你的论证:
            1. 回应反方的具体质疑点
            2. 必须调用 recordDebateScript 工具记录发言
            3. 必须调用 SayToUser 工具输出观点
            
            请开始你的第二轮发言！
            """.trimIndent()
        }

        val retrieveProRound2 by node<StringSubgraphResult, String> { 
            val result = it.result
            println("🟢 正方第二轮 (OpenAI GPT-4o) 完成")
            result
        }

        // 第二轮：反方再反驳 (这是新增的)
        val conRound2 by subgraphWithTask<String>(
            tools = scriptToolSet.asTools() + listOf(SayToUser),
            llmModel = GoogleModels.Gemini2_0Flash
        ) { userInput ->
            """
            🔴 你是反方辩手第二轮 (Google Gemini)
            
            基于正方的第二轮论证，请进一步反驳:
            1. 回应正方在第二轮提出的新论点
            2. 必须调用 recordDebateScript 工具记录发言
            3. 必须调用 SayToUser 工具输出观点
            
            请开始你的第二轮反驳！
            """.trimIndent()
        }

        val retrieveConRound2 by node<StringSubgraphResult, String> { 
            val result = it.result
            println("🔴 反方第二轮 (Google Gemini) 完成")
            result
        }

        // 反方总结陈词
        val conSummary by subgraphWithTask<String>(
            tools = opinionToolSet.asTools() + scriptToolSet.asTools() + listOf(SayToUser),
            llmModel = GoogleModels.Gemini2_0Flash
        ) { userInput ->
            """
            🔴 你是反方辩手总结陈词 (Google Gemini)
            
            请进行总结陈词:
            1. 总结你的核心反对理由
            2. 必须调用 recordDebateScript 工具记录发言
            3. 必须调用 SayToUser 工具输出观点
            4. 可选使用 analyzeDebateOpinions 工具分析整场辩论
            
            请开始总结陈词！
            """.trimIndent()
        }

        val retrieveConSummary by node<StringSubgraphResult, String> { 
            val result = it.result
            println("🔴 反方总结 (Google Gemini) 完成")
            result
        }

        // 正方总结陈词
        val proSummary by subgraphWithTask<String>(
            tools = opinionToolSet.asTools() + scriptToolSet.asTools() + listOf(SayToUser),
            llmModel = OpenAIModels.Chat.GPT4o
        ) { userInput ->
            """
            🟢 你是正方辩手总结陈词 (OpenAI GPT-4o)
            
            请进行总结陈词:
            1. 总结你的核心支持理由
            2. 必须调用 recordDebateScript 工具记录发言
            3. 必须调用 SayToUser 工具输出观点
            4. 可选使用 analyzeDebateOpinions 工具分析整场辩论
            
            请开始总结陈词！
            """.trimIndent()
        }

        val retrieveProSummary by node<StringSubgraphResult, String> {
            val result = it.result
            println("🟢 正方总结 (OpenAI GPT-4o) 完成")
            result
        }

        // 辩论主持人点评和判定 - 使用第三方LLM (Anthropic Claude)
        val debateHostJudgment by subgraphWithTask<String>(
            tools = opinionToolSet.asTools() + scriptToolSet.asTools() + listOf(SayToUser),
            llmModel = OpenRouterModels.Claude3Haiku  // 使用Claude作为公正的第三方评判
        ) { userInput ->
            """
            🎙️ 你是辩论主持人和评委 (Anthropic Claude 3.5 Sonnet - 第三方中立评判)
            
            辩论主题: $userInput
            
            现在辩论双方已完成所有轮次的发言，请作为专业的辩论主持人：
            
            1. 📋 **汇总整场辩论**：
               - 简要总结正方的核心观点和主要论据
               - 简要总结反方的核心观点和主要论据
               - 分析双方论证的逻辑结构
            
            2. 🔍 **专业点评**：
               - 评价双方论据的说服力和逻辑严密性
               - 指出各方论证的亮点和不足之处  
               - 分析辩论中的关键转折点
               - 评估双方对对手论点的回应效果
            
            3. ⚖️ **获胜判定**：
               - 基于以下标准进行综合评判：
                 * 论据的充分性和说服力 (30%)
                 * 逻辑推理的严密性 (25%)
                 * 对对方观点的有效回应 (20%)
                 * 论述的清晰度和表达力 (15%)
                 * 论点的创新性和深度 (10%)
               - 明确宣布获胜方：🟢 正方获胜 或 🔴 反方获胜
               - 详细说明获胜理由
            
            4. 📊 **评分统计**：
               - 给双方各项能力打分 (满分10分)
               - 提供总体评分和排名
            
            请务必：
            - 调用 recordDebateScript 工具记录你的主持评判
            - 调用 SayToUser 工具输出完整的主持人总结
            - 可选调用 analyzeDebateOpinions 工具进行深度分析
            
            作为中立的第三方AI，请公正客观地进行评判！
            """.trimIndent()
        }

        val finalizeDebateWithJudgment by node<StringSubgraphResult, String> {
            val result = it.result
            println("🎙️ 辩论主持人 (Claude 3.5 Sonnet) 评判完成")
            
            """
            🏆 AI vs AI 辩论大赛圆满结束！
            
            📊 本次辩论统计：
            - 🟢 正方代表: OpenAI GPT-4o (逻辑推理专长)
            - 🔴 反方代表: Google Gemini (创新思维专长)  
            - 🎙️ 主持评判: Anthropic Claude 3.5 Sonnet (第三方中立)
            
            💾 完整辩论记录和评判结果已保存至内存系统
            🎯 感谢观看这场精彩的AI智能对决！
            """.trimIndent()
        }

        val saveToMemory by subgraph<String, String>(tools = emptyList()) {
            val saveDebater by nodeSaveToMemory<String>(
                concept = debaterConcept,
                subject = MemorySubjects.Debater,
                scope = MemoryScopeType.PRODUCT
            )

            val saveOpinion by nodeSaveToMemory<String>(
                concept = opinionConcept,
                subject = MemorySubjects.Opinion,
                scope = MemoryScopeType.PRODUCT
            )

            val saveFinalScript by nodeSaveToMemory<String>(
                concept = scriptConcept,
                subject = MemorySubjects.Script,
                scope = MemoryScopeType.PRODUCT
            )

            nodeStart then saveDebater then saveOpinion then saveFinalScript then nodeFinish
        }

        // 完整的辩论流程 + 主持人评判
        nodeStart then loadMemory then 
        proDebateSession then retrieveProResult then saveProToMemory then
        conDebateSession then retrieveConResult then saveConToMemory then
        proRound2 then retrieveProRound2 then 
        conRound2 then retrieveConRound2 then
        conSummary then retrieveConSummary then  
        proSummary then retrieveProSummary then  
        debateHostJudgment then finalizeDebateWithJudgment then saveToMemory then nodeFinish  // 新增主持人评判
    }

    return AIAgent(
        promptExecutor = multiExecutor,
        strategy = strategy,
        agentConfig = agentConfig,
        toolRegistry = ToolRegistry {
            tools(debaterToolSet.asTools())
            tools(opinionToolSet.asTools())
            tools(scriptToolSet.asTools())
            tools(hostToolSet.asTools())  // 新增主持人工具

            tool(SayToUser)
            tool(ProvideStringSubgraphResult)
        }
    ) {
        install(AgentMemory) {
            this.memoryProvider = memoryProvider

            if (featureName != null) this.featureName = featureName
            if (productName != null) this.productName = productName
            if (organizationName != null) this.organizationName = organizationName
        }
    }
}

/**
 * 辩方管理工具集
 */
@LLMDescription("管理辩论双方真实AI对话的工具")
class DebaterToolSet : ToolSet {

    @Tool
    @LLMDescription("分析辩论策略")
    fun analyzeDebaterStrategy(
        @LLMDescription("当前辩论轮次和策略分析")
        strategyInfo: String
    ): String {
        return "策略分析：$strategyInfo - 建议注重逻辑严密性和事实依据"
    }
}

/**
 * 论点管理工具集
 */
@LLMDescription("管理和分析辩论论点的工具")
class OpinionToolSet : ToolSet {

    @Tool
    @LLMDescription("分析和总结辩论论点")
    fun analyzeDebateOpinions(
        @LLMDescription("辩论中的关键论点和观点")
        opinionsData: String
    ): String {
        return """
            论点分析结果：
            关键论点: $opinionsData
            论点强度评估：逻辑性、证据支持、说服力已评估
            建议改进方向：增强事实依据、完善逻辑链条
        """.trimIndent()
    }
}

/**
 * 辞管理工具集
 */
@LLMDescription("记录和管理AI辩论发言的工具")
class ScriptToolSet : ToolSet {

    @Tool
    @LLMDescription("记录辩论发言。辩手必须使用此工具记录自己的发言内容。")
    fun recordDebateScript(
        @LLMDescription("辩论发言内容")
        scriptContent: String
    ): String {
        println("📝 辩论发言已记录")
        return "发言记录成功：内容已保存至辩论记录系统"
    }
}

/**
 * 辩论系统主函数
 */
fun main(): kotlin.Unit = runBlocking {
    // 示例加密密钥
    val secretKey = "7UL8fsTqQDq9siUZgYO3bLGqwMGXQL4vKMWMscKB7Cw="

    // 清理旧的内存数据
    val memoryPath = Path("./debate-memory/")
    try {
        if (memoryPath.toFile().exists()) {
            memoryPath.toFile().deleteRecursively()
            println("🧹 已清理旧的内存数据")
        }
    } catch (e: Exception) {
        println("⚠️ 清理内存数据时出现警告: ${e.message}")
    }

    // 设置日志级别，减少噪音
    System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "WARN")
    System.setProperty("org.slf4j.simpleLogger.log.ai.koog.agents.memory.feature.AgentMemory", "ERROR")
    System.setProperty("org.slf4j.simpleLogger.log.ai.koog.prompt.structure.StructureParser", "ERROR")
    
    // 创建内存提供者
    val memoryProvider = LocalFileMemoryProvider(
        config = LocalMemoryConfig("debate-memory"),
        storage = EncryptedStorage(
            fs = JVMFileSystemProvider.ReadWrite,
            encryption = Aes256GCMEncryptor(secretKey)
        ),
        fs = JVMFileSystemProvider.ReadWrite,
        root = memoryPath,
    )

    // 创建并运行辩论主持人代理
    val agent = createDebateHostAgent(
        debaterToolSet = DebaterToolSet(),
        opinionToolSet = OpinionToolSet(),
        scriptToolSet = ScriptToolSet(),
        hostToolSet = DebateHostToolSet(),  // 新增主持人工具集
        memoryProvider = memoryProvider,
        featureName = "debate-host",
        productName = "debate-system",
        organizationName = "ai-debate",
        maxAgentIterations = 250  // 增加迭代次数以应对更复杂的流程
    )
    
    // 开始辩论
    println("🎭 开始 AI vs AI 辩论...")
    try {
        val result = agent.run("人工智能是否会取代人类工作")
        println("\n🎉 辩论最终结果：\n$result")
    } catch (e: Exception) {
        when {
            e.message?.contains("MissingFieldException") == true || 
            e.message?.contains("StructureParser") == true -> {
                println("❌ 内存数据格式错误，已尝试清理内存目录")
                println("如果问题持续，请手动删除 ./debate-memory/ 目录后重试")
            }
            e.message?.contains("MaxNumberOfIterationsReachedException") == true -> {
                println("❌ Agent 达到最大迭代次数限制")
                println("💡 建议：进一步增加 maxAgentIterations 参数或简化辩论话题")
            }
            else -> {
                println("❌ 辩论过程中出现错误：${e.message}")
                println("💡 建议：检查网络连接和 API 密钥配置")
            }
        }
        e.printStackTrace()
    }
}

internal object ApiKeyService {
    val openAIApiKey: String
        get() = System.getenv("OPENAI_API_KEY") ?: throw IllegalArgumentException("OPENAI_API_KEY env is not set")

    val anthropicApiKey: String
        get() = System.getenv("ANTHROPIC_API_KEY") ?: throw IllegalArgumentException("ANTHROPIC_API_KEY env is not set")

    val googleApiKey: String
        get() = System.getenv("GOOGLE_API_KEY") ?: throw IllegalArgumentException("GOOGLE_API_KEY env is not set")

    val openRouterApiKey: String
        get() = System.getenv("OPENROUTER_API_KEY") ?: throw IllegalArgumentException("OPENROUTER_API_KEY env is not set")

    val awsAccessKey: String
        get() = System.getenv("AWS_ACCESS_KEY_ID") ?: throw IllegalArgumentException("AWS_ACCESS_KEY_ID env is not set")

    val awsSecretAccessKey: String
        get() = System.getenv("AWS_SECRET_ACCESS_KEY") ?: throw IllegalArgumentException("AWS_SECRET_ACCESS_KEY env is not set")
}

/**
 * 主持人评判工具集
 */
@LLMDescription("辩论主持人专用的评判和总结工具")
class DebateHostToolSet : ToolSet {

    @Tool
    @LLMDescription("评判辩论获胜方并给出详细评分")
    fun judgeDebateWinner(
        @LLMDescription("获胜方 (正方 或 反方)")
        winner: String,
        @LLMDescription("获胜理由和评分详情")
        judgmentDetails: String
    ): String {
        val winnerEmoji = if (winner.contains("正方")) "🟢" else "🔴"
        return """
            🏆 辩论评判结果
            
            获胜方: $winnerEmoji $winner
            
            评判详情:
            $judgmentDetails
            
            评判已记录至辩论档案系统
        """.trimIndent()
    }

    @Tool
    @LLMDescription("分析整场辩论的亮点和关键转折点")
    fun analyzeDebateHighlights(
        @LLMDescription("辩论亮点和转折点分析")
        highlightsAnalysis: String
    ): String {
        return """
            ✨ 辩论精彩分析
            
            $highlightsAnalysis
            
            分析结果已存档
        """.trimIndent()
    }
}
