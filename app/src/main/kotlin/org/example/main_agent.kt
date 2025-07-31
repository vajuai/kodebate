package org.example

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.asTool
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.ext.tool.AskUser
import ai.koog.agents.ext.tool.ExitTool
import ai.koog.agents.ext.tool.SayToUser
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.llms.all.simpleOpenAIExecutor
import kotlinx.coroutines.runBlocking

// 创建多轮对话主 Agent（使用 String 作为输入输出类型）
suspend fun createMainChatAgent(): AIAgent<String, String> {
    val openAIApiToken = System.getenv("OPENAI_API_KEY") ?: error("OPENAI_API_KEY environment variable not set")

    println("🔧 [MainAgent] 正在创建子 Agent...")

    // 将笑话 Agent 包装成工具
    val jokeAgentTool = createJokeAgent().asTool(
        agentName = "joke_generator",
        agentDescription = "Generate the best joke about a given topic using multiple AI models and select the funniest one",
        inputDescriptor = ToolParameterDescriptor(
            name = "topic",
            description = "The topic to generate a joke about (e.g., 'programming', 'cats', 'coffee')",
            type = ToolParameterType.String
        )
    )

    // 将诗词 Agent 包装成工具
    val poemAgentTool = createPoemAgent().asTool(
        agentName = "poem_generator",
        agentDescription = "Generate the best Chinese and English poems about a given keyword using multiple AI models and select the most relevant ones",
        inputDescriptor = ToolParameterDescriptor(
            name = "keyword",
            description = "The keyword to generate poems about (e.g., '春天', 'love', '月亮', 'nature')",
            type = ToolParameterType.String
        )
    )

    // 使用天气 Agent - 基于 weather.kt 中的 createWeatherAgent()
    val weatherAgentTool = createWeatherAgent().asTool(
        agentName = "weather_assistant",
        agentDescription = "Get weather information for any city worldwide. Returns beautifully formatted weather information.",
        inputDescriptor = ToolParameterDescriptor(
            name = "query",
            description = "Weather query - can be a single city name (e.g., 'Beijing', 'London', 'New York') or multiple cities (e.g., 'Tokyo,Paris,London')",
            type = ToolParameterType.String
        )
    )

    /*
    // 将辩论 Agent 包装成工具
    val debateAgentTool = createDebateHost().asTool(
        agentName = "debate_system",
        agentDescription = "AI自动辩论系统",
        inputDescriptor = ToolParameterDescriptor(
            name = "topic",
            description = "辩论话题",
            type = ToolParameterType.String
        )
    )
    */

    // 创建包含所有内置工具和专用 Agent 工具的工具注册表
    val toolRegistry = ToolRegistry {
        tool(SayToUser)
        tool(AskUser)
        tool(ExitTool)
        tool(jokeAgentTool)
        tool(poemAgentTool)
        tool(weatherAgentTool)
        //tool(debateAgentTool)  // 新添加的辩论工具
    }

    println("🛠️ [MainAgent] 主 Agent 工具注册表创建完成")

    return AIAgent(
        executor = simpleOpenAIExecutor(openAIApiToken),
        systemPrompt = """
            You are a friendly chat assistant that can engage in multi-turn conversations.
            
            CRITICAL RULES - YOU MUST FOLLOW THESE ALWAYS:
            1. You MUST ALWAYS use tools to communicate with the user
            2. NEVER provide direct responses without using tools
            3. Every response must be through a tool call
            4. You cannot speak directly to the user - only through tools
            
            Available tools:
            - SayToUser: Send messages to the user (USE THIS FOR ALL COMMUNICATIONS)
            - AskUser: Ask the user for input and wait for their response
            - ExitTool: End the conversation when the user wants to quit
            - joke_generator: Generate high-quality jokes about any topic
            - poem_generator: Generate beautiful Chinese and English poems about any keyword
            - weather_assistant: Get current weather information for any city worldwide (returns formatted output)
            - debate_system: Start an AI debate system with pro and con agents arguing about a topic
            
            Response guidelines:
            - For jokes: Use joke_generator tool first, then use SayToUser to share the result with prefix "🎭 [笑话助手] "
            - For poems/poetry: Use poem_generator tool first, then use SayToUser to share the result with prefix "🎨 [诗词助手] "
            - For weather queries: Use weather_assistant tool first, then use SayToUser to share the result with prefix "🌤️ [天气助手] "
            - For debate requests: Use debateAgentTool, then use SayToUser to share the result with prefix "🎯 [辩论助手] "
            - For general chat: Use SayToUser to respond with prefix "💬 [聊天助手] "
            - For questions: Use AskUser to ask for clarification
            - For exit requests: Use ExitTool
            
            Weather queries include:
            - Single city: "What's the weather like in Beijing?"
            - Multiple cities: "Weather in Tokyo,Paris,London" or "How's the weather in New York and Shanghai?"
            
            Debate queries include:
            - "Let's debate about AI replacing human jobs"
            - "Start a debate on social media impact"
            - "I want to see a debate about remote work vs office work"
            
            IMPORTANT: 
            - For weather queries, first extract the city name(s), and then extract current (today or 今天) or future (tomorrow or 明天 or 后天 or future dates) and pass them to weather_assistant tool
            - For debate queries, extract the main topic and pass it to debate_system tool
            - The weather_assistant already returns beautifully formatted weather information
            - The debate_system will conduct a full debate with multiple rounds and provide the final judgment
            - Simply pass the results directly to SayToUser with the appropriate prefix
            - Always add the appropriate agent prefix to identify which agent provided the response
                        
            REMEMBER: You must use SayToUser tool for every single response to the user. No exceptions.
        """.trimIndent(),
        llmModel = OpenAIModels.Chat.GPT4o,
        toolRegistry = toolRegistry,
        strategy = poemChatStrategy // 使用诗词聊天策略
    )
}

// 多轮对话主循环
fun main() = runBlocking {
    println("🔍 [MainAgent] 检查环境变量...")
    println("OPENAI_API_KEY: ${System.getenv("OPENAI_API_KEY")?.take(10)}...")
    println("OWM_API_KEY: ${System.getenv("OWM_API_KEY")?.take(10)}...")
    println("OPENROUTER_API_KEY: ${System.getenv("OPENROUTER_API_KEY")?.take(10)}...")
    println("GEMINI_API_KEY: ${System.getenv("GEMINI_API_KEY")?.take(10)}...")
    
    try {
        val agent = createMainChatAgent()

        println("🎭 欢迎使用多轮对话助手！")
        println("💬 我可以与您聊天、生成笑话、创作诗歌、查询天气信息、进行AI辩论！")
        println("🎨 您可以尝试询问：笑话、诗歌、天气信息，辩论、或者直接与我聊天！")
        println("🚪 输入 'quit'、'exit' 或 'bye' 结束对话")

        var sessionActive = true

        while (sessionActive) {
            print("👤 您: ")
            val userInput = readlnOrNull() ?: ""

            if (userInput.trim().isEmpty()) {
                continue
            }

            // 检查是否应该结束会话
            if (userInput.lowercase().trim() in listOf("quit", "exit", "bye", "退出", "结束")) {
                sessionActive = false
                println("👋 感谢您的使用！再见！")
                break
            }

            try {
                agent.run(userInput)
            } catch (e: Exception) {
                println("❌ 错误: ${e.message}")
                println("🔄 让我们继续聊天...")
                e.printStackTrace()
            }
        }
    } catch (e: Exception) {
        println("❌ [MainAgent] 初始化失败: ${e.message}")
        e.printStackTrace()
    }
}