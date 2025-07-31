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
data class PoemSelection(
    val chineseIndex: Int,
    val englishIndex: Int,
    val chineseReason: String,
    val englishReason: String
)

// 诗词生成策略
val poemStrategy = strategy("best-poem") {
    // 中文古诗词生成节点
    val nodeChineseOpenAI by node<String, String> { keyword ->
        println("🤖 [PoemAgent] OpenAI GPT-4.1 正在生成中文古诗词...")
        llm.writeSession {
            model = OpenAIModels.Chat.GPT4_1
            updatePrompt {
                system("你是一位中国古代诗人，精通各种诗体。请根据给定的关键词创作一首相关的中国古诗词。只返回诗词内容，不要解释。")
                user("请以「$keyword」为主题创作一首古诗词。")
            }
            val response = requestLLMWithoutTools()
            val poem = response.content
            println("✅ [PoemAgent] OpenAI 中文古诗词完成: $poem")
            poem
        }
    }

    val nodeChineseClaude by node<String, String> { keyword ->
        println("🤖 [PoemAgent] Claude 3 Haiku 正在生成中文古诗词...")
        llm.writeSession {
            model = OpenRouterModels.Claude3Haiku
            updatePrompt {
                system("你是一位中国古代诗人，精通各种诗体。请根据给定的关键词创作一首相关的中国古诗词。只返回诗词内容，不要解释。")
                user("请以「$keyword」为主题创作一首古诗词。")
            }
            val response = requestLLMWithoutTools()
            val poem = response.content
            println("✅ [PoemAgent] Claude 中文古诗词完成: $poem")
            poem
        }
    }

    val nodeChineseGemini by node<String, String> { keyword ->
        println("🤖 [PoemAgent] Gemini 2.0 Flash 正在生成中文古诗词...")
        llm.writeSession {
            model = GoogleModels.Gemini2_0Flash
            updatePrompt {
                system("你是一位中国古代诗人，精通各种诗体。请根据给定的关键词创作一首相关的中国古诗词。只返回诗词内容，不要解释。")
                user("请以「$keyword」为主题创作一首古诗词。")
            }
            val response = requestLLMWithoutTools()
            val poem = response.content
            println("✅ [PoemAgent] Gemini 中文古诗词完成: $poem")
            poem
        }
    }

    // 英文诗词生成节点
    val nodeEnglishOpenAI by node<String, String> { keyword ->
        println("🤖 [PoemAgent] OpenAI GPT-4.1 正在生成英文诗词...")
        llm.writeSession {
            model = OpenAIModels.Chat.GPT4_1
            updatePrompt {
                system("You are a skilled English poet. Create a beautiful English poem based on the given keyword. Provide only the poem content, no explanations.")
                user("Write an English poem about '$keyword'.")
            }
            val response = requestLLMWithoutTools()
            val poem = response.content
            println("✅ [PoemAgent] OpenAI 英文诗词完成: $poem")
            poem
        }
    }

    val nodeEnglishClaude by node<String, String> { keyword ->
        println("🤖 [PoemAgent] Claude 3 Haiku 正在生成英文诗词...")
        llm.writeSession {
            model = OpenRouterModels.Claude3Haiku
            updatePrompt {
                system("You are a skilled English poet. Create a beautiful English poem based on the given keyword. Provide only the poem content, no explanations.")
                user("Write an English poem about '$keyword'.")
            }
            val response = requestLLMWithoutTools()
            val poem = response.content
            println("✅ [PoemAgent] Claude 英文诗词完成: $poem")
            poem
        }
    }

    val nodeEnglishGemini by node<String, String> { keyword ->
        println("🤖 [PoemAgent] Gemini 2.0 Flash 正在生成英文诗词...")
        llm.writeSession {
            model = GoogleModels.Gemini2_0Flash
            updatePrompt {
                system("You are a skilled English poet. Create a beautiful English poem based on the given keyword. Provide only the poem content, no explanations.")
                user("Write an English poem about '$keyword'.")
            }
            val response = requestLLMWithoutTools()
            val poem = response.content
            println("✅ [PoemAgent] Gemini 英文诗词完成: $poem")
            poem
        }
    }

    // 并行生成所有诗词并选择最佳的
    val nodeGenerateBestPoems by parallel(
        nodeChineseOpenAI, nodeChineseClaude, nodeChineseGemini,
        nodeEnglishOpenAI, nodeEnglishClaude, nodeEnglishGemini
    ) {
        selectByIndex { allPoems ->
            println("\n🎯 [PoemAgent] 所有模型生成完成，正在选择最佳诗词...")
            
            // 分离中文和英文诗词
            val chinesePoems = allPoems.take(3)
            val englishPoems = allPoems.drop(3)
            
            println("📋 [PoemAgent] 生成的中文古诗词:")
            chinesePoems.forEachIndexed { index, poem ->
                println("   ${index + 1}. $poem")
            }
            
            println("\n📋 [PoemAgent] 生成的英文诗词:")
            englishPoems.forEachIndexed { index, poem ->
                println("   ${index + 1}. $poem")
            }

            // 使用另一个LLM来选择最佳诗词
            try {
                llm.writeSession {
                    model = OpenAIModels.Chat.GPT4o
                    updatePrompt {
                        prompt("best-poem-selector") {
                            system("你是一位诗歌鉴赏家，精通中英文诗词。请从提供的选项中选择最贴近主题的中文和英文诗词各一首。")
                            user(
                                """
                                以下是三首中文古诗词:
                                
                                ${chinesePoems.mapIndexed { index, poem -> "中文诗词 ${index + 1}:\n$poem" }.joinToString("\n\n")}
                                
                                以下是三首英文诗词:
                                
                                ${englishPoems.mapIndexed { index, poem -> "English Poem ${index + 1}:\n$poem" }.joinToString("\n\n")}

                                请选择最贴近主题的中文和英文诗词各一首，并说明选择理由。
                                返回JSON格式，包含：
                                - chineseIndex: 中文诗词的索引 (1, 2, 或 3)
                                - englishIndex: 英文诗词的索引 (1, 2, 或 3)
                                - chineseReason: 选择该中文诗词的理由
                                - englishReason: 选择该英文诗词的理由
                                
                                确保索引在1到3之间。
                                """.trimIndent()
                            )
                        }
                    }

                    val response = requestLLMStructured(JsonStructuredData.createJsonStructure<PoemSelection>())
                    val bestPoems = response.getOrNull()?.structure

                    // 确保索引在有效范围内
                    val validChineseIndex = when {
                        bestPoems?.chineseIndex == null -> 1
                        bestPoems.chineseIndex < 1 -> 1
                        bestPoems.chineseIndex > 3 -> 3
                        else -> bestPoems.chineseIndex
                    }
                    
                    val validEnglishIndex = when {
                        bestPoems?.englishIndex == null -> 1
                        bestPoems.englishIndex < 1 -> 1
                        bestPoems.englishIndex > 3 -> 3
                        else -> bestPoems.englishIndex
                    }

                    val selectedChineseIndex = validChineseIndex - 1 // 转换为0-based索引
                    val selectedEnglishIndex = validEnglishIndex - 1 // 英文诗词索引

                    println("🏆 [PoemAgent] GPT-4o 选择了第 $validChineseIndex 首中文诗词")
                    println("📝 [PoemAgent] 中文选择理由: ${bestPoems?.chineseReason ?: "Default selection"}")
                    println("🎉 [PoemAgent] 最终中文诗词: ${chinesePoems[selectedChineseIndex]}")
                    
                    println("\n🏆 [PoemAgent] GPT-4o 选择了第 $validEnglishIndex 首英文诗词")
                    println("📝 [PoemAgent] 英文选择理由: ${bestPoems?.englishReason ?: "Default selection"}")
                    println("🎉 [PoemAgent] 最终英文诗词: ${englishPoems[selectedEnglishIndex]}")

                    // 返回中文诗词的索引（我们选择返回中文诗词作为主要结果）
                    selectedChineseIndex
                }
            } catch (e: Exception) {
                println("⚠️ [PoemAgent] 选择过程出错，默认选择第一首中文诗词: ${e.message}")
                0 // 默认选择第一首中文诗词
            }
        }
    }

    // 连接节点
    nodeStart then nodeGenerateBestPoems then nodeFinish
}

// 创建 poemAgent 的工厂函数
fun createPoemAgent(): AIAgent<String, String> {
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
            你是一位诗歌助手，专门生成中文古诗词。
            你会根据给定的关键词生成最佳的中文古诗词。
            使用多个AI模型生成不同的诗词，然后选择最佳的作品。
            只返回选中的中文诗词内容，不要添加任何解释。
        """.trimIndent(),
        llmModel = OpenAIModels.Chat.GPT4_1,
        toolRegistry = toolRegistry,
        strategy = poemStrategy,
    )
}

// 主 Agent 的策略 - 修改为不允许直接回复
val poemChatStrategy = strategy("Multi-turn Poem Chat") {
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