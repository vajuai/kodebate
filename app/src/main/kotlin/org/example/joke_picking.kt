package org.example

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.dsl.builder.forwardTo
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeExecuteTool
import ai.koog.agents.core.dsl.extension.nodeLLMRequest
import ai.koog.agents.core.dsl.extension.nodeLLMSendToolResult
import ai.koog.agents.core.dsl.extension.onAssistantMessage
import ai.koog.agents.core.dsl.extension.onToolCall
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient
import ai.koog.prompt.executor.clients.openrouter.OpenRouterModels
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.structure.json.JsonStructuredData
import kotlinx.serialization.Serializable


// 定义用于结构化响应的数据类
@Serializable
data class JokeSelection(
    val index: Int,
    val reason: String
)

// 修复后的笑话生成策略
val jokeStrategy = strategy("best-joke") {
    // Define nodes for different LLM models
    val nodeOpenAI by node<String, String> { topic ->
        println("🤖 [JokeAgent] OpenAI GPT-4.1 正在生成笑话...")
        llm.writeSession {
            model = OpenAIModels.Chat.GPT4_1
            updatePrompt {
                system("You are a comedian. Generate a funny joke about the given topic. Provide only the joke, no explanations.")
                user("Tell me a joke about $topic.")
            }
            val response = requestLLMWithoutTools()
            val joke = response.content
            println("✅ [JokeAgent] OpenAI 完成: $joke")
            joke
        }
    }

    val nodeClaudeHaiku by node<String, String> { topic ->
        println("🤖 [JokeAgent] Claude 3 Haiku 正在生成笑话...")
        llm.writeSession {
            model = OpenRouterModels.Claude3Haiku
            updatePrompt {
                system("You are a comedian. Generate a funny joke about the given topic. Provide only the joke, no explanations.")
                user("Tell me a joke about $topic.")
            }
            val response = requestLLMWithoutTools()
            val joke = response.content
            println("✅ [JokeAgent] Claude 完成: $joke")
            joke
        }
    }

    val nodeGeminiFlash by node<String, String> { topic ->
        println("🤖 [JokeAgent] Gemini 2.0 Flash 正在生成笑话...")
        llm.writeSession {
            model = GoogleModels.Gemini2_0Flash
            updatePrompt {
                system("You are a comedian. Generate a funny joke about the given topic. Provide only the joke, no explanations.")
                user("Tell me a joke about $topic.")
            }
            val response = requestLLMWithoutTools()
            val joke = response.content
            println("✅ [JokeAgent] Gemini 完成: $joke")
            joke
        }
    }

    // Execute joke generation in parallel and select the best joke
    val nodeGenerateBestJoke by parallel(
        nodeOpenAI, nodeClaudeHaiku, nodeGeminiFlash,
    ) {
        selectByIndex { jokes ->
            println("\n🎯 [JokeAgent] 所有模型生成完成，正在选择最佳笑话...")
            println("📋 [JokeAgent] 生成的笑话:")
            jokes.forEachIndexed { index, joke ->
                println("   ${index + 1}. $joke")
            }

            // Another LLM (e.g., GPT4o) would find the funniest joke:
            try {
                llm.writeSession {
                    model = OpenAIModels.Chat.GPT4o
                    updatePrompt {
                        prompt("best-joke-selector") {
                            system("You are a comedy critic. Select the best joke from the provided options.")
                            user(
                                """
                                Here are three jokes about the same topic:

                                ${jokes.mapIndexed { index, joke -> "Joke ${index + 1}:\n$joke" }.joinToString("\n\n")}

                                Select the best joke by providing the index (1, 2, or 3) and explain why it's the best.
                                Respond with a JSON object containing:
                                - index: the 1-based index of the best joke (1, 2, or 3)
                                - reason: explanation of why this joke is the best
                                
                                Make sure the index is between 1 and 3 inclusive.
                                """.trimIndent()
                            )
                        }
                    }

                    val response = requestLLMStructured(JsonStructuredData.createJsonStructure<JokeSelection>())
                    val bestJoke = response.getOrNull()?.structure

                    // 确保索引在有效范围内
                    val validIndex = when {
                        bestJoke?.index == null -> 1
                        bestJoke.index < 1 -> 1
                        bestJoke.index > 3 -> 3
                        else -> bestJoke.index
                    }

                    val selectedIndex = validIndex - 1 // 转换为0-based索引

                    println("🏆 [JokeAgent] GPT-4o 选择了第 $validIndex 个笑话")
                    println("📝 [JokeAgent] 理由: ${bestJoke?.reason ?: "Default selection"}")
                    println("🎉 [JokeAgent] 最终选择: ${jokes[selectedIndex]}")

                    selectedIndex
                }
            } catch (e: Exception) {
                println("⚠️ [JokeAgent] 选择过程出错，默认选择第一个笑话: ${e.message}")
                0 // 默认选择第一个笑话
            }
        }
    }

    // Connect the nodes
    nodeStart then nodeGenerateBestJoke then nodeFinish
}

// 创建 jokeAgent 的工厂函数（使用 String 作为输入输出类型）
fun createJokeAgent(): AIAgent<String, String> {
    val openAIApiToken = System.getenv("OPENAI_API_KEY") ?: error("OPENAI_API_KEY environment variable not set")
    val openRouterApiToken = System.getenv("OPENROUTER_API_KEY") ?: error("OPENROUTER_API_KEY environment variable not set")
    val googleApiToken = System.getenv("GEMINI_API_KEY") ?: error("GEMINI_API_KEY environment variable not set")

    val openAIClient = OpenAILLMClient(openAIApiToken)
    val openRouterClient = OpenRouterLLMClient(openRouterApiToken)
    val googleClient = GoogleLLMClient(googleApiToken)

    val multiExecutor = MultiLLMPromptExecutor(
        LLMProvider.OpenAI to openAIClient,
        LLMProvider.OpenRouter to openRouterClient,
        LLMProvider.Google to googleClient
    )

    val toolRegistry = ToolRegistry {
        // 空的工具注册表
    }

    return AIAgent(
        executor = multiExecutor,
        systemPrompt = """
            You are a comedy assistant that generates and selects the best jokes.
            You will be given a topic and you need to find the funniest joke about that topic.
            Use multiple AI models to generate different jokes and then select the best one.
            Return only the selected joke, nothing else.
        """.trimIndent(),
        llmModel = OpenAIModels.Chat.GPT4_1,
        toolRegistry = toolRegistry,
        strategy = jokeStrategy, // 使用修复后的策略
    )
}

// 主 Agent 的策略 - 修改为不允许直接回复
val chatStrategy = strategy("Multi-turn Chat") {
    val nodeAnalyzeInput by nodeLLMRequest()
    val nodeExecuteTool by nodeExecuteTool()
    val nodeSendToolResult by nodeLLMSendToolResult()

    // 连接节点 - 移除直接回复的边
    edge(nodeStart forwardTo nodeAnalyzeInput)
    // 移除这条边，强制使用工具
    // edge(nodeAnalyzeInput forwardTo nodeFinish onAssistantMessage { true })

    // 只允许工具调用
    edge(nodeAnalyzeInput forwardTo nodeExecuteTool onToolCall { true })
    edge(nodeExecuteTool forwardTo nodeSendToolResult)
    edge(nodeSendToolResult forwardTo nodeFinish onAssistantMessage { true })
    edge(nodeSendToolResult forwardTo nodeExecuteTool onToolCall { true })
}

