package org.example

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.tool
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.llms.all.simpleOpenAIExecutor
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*

// 当前天气数据结构
@Serializable
data class WeatherResponse(
    val name: String,
    val sys: Sys,
    val main: Main,
    val weather: List<Weather>,
    val wind: Wind,
    val visibility: Int,
    val coord: Coord
)

// 5天预报数据结构
@Serializable
data class ForecastResponse(
    val city: ForecastCity,
    val list: List<ForecastItem>
)

@Serializable
data class ForecastCity(
    val name: String,
    val country: String
)

@Serializable
data class ForecastItem(
    val dt: Long,
    val main: Main,
    val weather: List<Weather>,
    val wind: Wind,
    val dt_txt: String
)

@Serializable
data class Main(
    val temp: Double,
    val feels_like: Double,
    val temp_min: Double,
    val temp_max: Double,
    val pressure: Int,
    val humidity: Int
)

@Serializable
data class Weather(
    val main: String,
    val description: String,
    val icon: String
)

@Serializable
data class Wind(
    val speed: Double,
    val deg: Int? = null
)

@Serializable
data class Sys(
    val country: String
)

@Serializable
data class Coord(
    val lon: Double,
    val lat: Double
)

// 全局 JSON 实例
private val json = Json { ignoreUnknownKeys = true }

// 工具函数 - 获取当前天气 (改进描述)
@Tool
@LLMDescription("Get CURRENT/REAL-TIME weather information for any city. Use this for 'now', 'current', 'currently', 'today', 'right now', '现在', '当前', '今天' queries.")
suspend fun getCurrentWeather(
    @LLMDescription("City name (e.g., 'Beijing', 'London', 'New York')")
    city: String
): String {
    println("🌤️ [WeatherAgent] 获取当前天气工具被调用...")
    val owmApiKey = System.getenv("OWM_API_KEY") ?: return "❌ Weather service not configured"

    return try {
        val encodedCity = URLEncoder.encode(city.trim(), "UTF-8")
        val url = URL("https://api.openweathermap.org/data/2.5/weather?q=$encodedCity&appid=$owmApiKey&units=metric")

        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10000
        connection.readTimeout = 10000

        if (connection.responseCode == 200) {
            val response = BufferedReader(InputStreamReader(connection.inputStream)).readText()
            val weatherData = json.decodeFromString<WeatherResponse>(response)
            formatCurrentWeatherResponse(weatherData)
        } else {
            val errorResponse = BufferedReader(InputStreamReader(connection.errorStream)).readText()
            println("🚨 [WeatherAgent] API 错误: $errorResponse")
            "❌ 无法获取 ${city} 的天气信息。请检查城市名称是否正确。"
        }
    } catch (e: Exception) {
        println("🚨 [WeatherAgent] 异常: ${e.message}")
        "❌ 天气服务暂时不可用，请稍后重试。"
    }
}

// 工具函数 - 获取5天天气预报 (改进描述)
@Tool
@LLMDescription("Get FUTURE weather forecast for any city. Use this for 'tomorrow', 'next', 'later', 'forecast', 'future', '明天', '后天', '未来', '预报', 'day after tomorrow' queries.")
suspend fun getWeatherForecast(
    @LLMDescription("City name (e.g., 'Beijing', 'London', 'New York')")
    city: String
): String {
    println("📅 [WeatherAgent] 获取天气预报工具被调用...")
    val owmApiKey = System.getenv("OWM_API_KEY") ?: return "❌ Weather service not configured"

    return try {
        val encodedCity = URLEncoder.encode(city.trim(), "UTF-8")
        val url = URL("https://api.openweathermap.org/data/2.5/forecast?q=$encodedCity&appid=$owmApiKey&units=metric")

        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10000
        connection.readTimeout = 10000

        if (connection.responseCode == 200) {
            val response = BufferedReader(InputStreamReader(connection.inputStream)).readText()
            val forecastData = json.decodeFromString<ForecastResponse>(response)
            formatForecastResponse(forecastData)
        } else {
            val errorResponse = BufferedReader(InputStreamReader(connection.errorStream)).readText()
            println("🚨 [WeatherAgent] API 错误: $errorResponse")
            "❌ 无法获取 ${city} 的天气预报。请检查城市名称是否正确。"
        }
    } catch (e: Exception) {
        println("🚨 [WeatherAgent] 异常: ${e.message}")
        "❌ 天气预报服务暂时不可用，请稍后重试。"
    }
}

// 工具函数 - 获取多个城市的天气 (改进描述)
@Tool
@LLMDescription("Get CURRENT weather for multiple cities at once. Use this for multiple city queries like 'Tokyo,Paris,London weather'.")
suspend fun getMultipleWeather(
    @LLMDescription("Comma-separated city names (e.g., 'Beijing,London,New York')")
    cities: String
): String {
    println("🌍 [WeatherAgent] 获取多城市天气工具被调用...")
    val cityList = cities.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    if (cityList.isEmpty()) return "❌ 请提供有效的城市名称"

    val results = mutableListOf<String>()
    for (city in cityList) {
        val weather = getCurrentWeather(city)
        results.add(weather)
    }

    return results.joinToString("\n\n════════════════════════════════════════════════════════════════\n\n")
}

// 格式化当前天气响应
private fun formatCurrentWeatherResponse(data: WeatherResponse): String {
    val weatherIcon = getWeatherIcon(data.weather.firstOrNull()?.main ?: "")
    val windDirection = getWindDirection(data.wind.deg ?: 0)

    return """
        $weatherIcon **${data.name}, ${data.sys.country} Current Weather:**
        
        - 🌡️ **Temperature:** ${data.main.temp.toInt()}°C (Feels like ${data.main.feels_like.toInt()}°C)
        - ☁️ **Condition:** ${data.weather.firstOrNull()?.description?.replaceFirstChar { it.uppercase() } ?: "Unknown"}
        - 💧 **Humidity:** ${data.main.humidity}%
        - 📊 **Pressure:** ${data.main.pressure} hPa
        - 🌬️ **Wind Speed:** ${(data.wind.speed * 3.6).toInt()} km/h (from $windDirection)
        - 👁️ **Visibility:** ${data.visibility / 1000.0} km
        
        **Temperature Range:** ${data.main.temp_min.toInt()}°C ~ ${data.main.temp_max.toInt()}°C
    """.trimIndent()
}

// 格式化预报响应
private fun formatForecastResponse(data: ForecastResponse): String {
    val forecast = StringBuilder()
    forecast.append("📅 **${data.city.name}, ${data.city.country} - Weather Forecast:**\n\n")

    // 按日期分组
    val groupedByDate = data.list.groupBy { item ->
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(item.dt * 1000))
    }

    groupedByDate.entries.take(5).forEach { (date, items) ->
        val dateFormatted = SimpleDateFormat("EEEE, MMM dd", Locale.getDefault()).format(
            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(date)!!
        )
        
        forecast.append("📆 **$dateFormatted:**\n")
        
        // 取当天的几个时间点
        items.take(3).forEach { item ->
            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(item.dt * 1000))
            val weatherIcon = getWeatherIcon(item.weather.firstOrNull()?.main ?: "")
            val condition = item.weather.firstOrNull()?.description?.replaceFirstChar { it.uppercase() } ?: "Unknown"
            
            forecast.append("  ⏰ $time: $weatherIcon ${item.main.temp.toInt()}°C, $condition\n")
        }
        forecast.append("\n")
    }

    return forecast.toString()
}

// 获取天气图标
private fun getWeatherIcon(condition: String): String {
    return when (condition.lowercase()) {
        "clear" -> "☀️"
        "clouds" -> "☁️"
        "rain" -> "🌧️"
        "drizzle" -> "🌦️"
        "thunderstorm" -> "⛈️"
        "snow" -> "❄️"
        "mist", "fog" -> "🌫️"
        "haze" -> "🌫️"
        else -> "🌤️"
    }
}

// 获取风向
private fun getWindDirection(degrees: Int): String {
    val directions = arrayOf("North", "Northeast", "East", "Southeast", "South", "Southwest", "West", "Northwest")
    val index = ((degrees + 22.5) / 45).toInt() % 8
    return directions[index]
}

// 获取当前日期时间字符串
private fun getCurrentDateTime(): String {
    val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss (EEEE)", Locale.getDefault())
    return formatter.format(Date())
}

// 创建天气 Agent (大幅改进系统提示)
suspend fun createWeatherAgent(): AIAgent<String, String> {
    val openAIApiToken = System.getenv("OPENAI_API_KEY") ?: error("OPENAI_API_KEY environment variable not set")

    val toolRegistry = ToolRegistry {
        tool(::getCurrentWeather)
        tool(::getWeatherForecast)
        tool(::getMultipleWeather)
    }

    return AIAgent(
        executor = simpleOpenAIExecutor(openAIApiToken),
        systemPrompt = """
            You are a weather assistant that provides accurate weather information for cities worldwide.
            
            CURRENT DATE AND TIME: ${getCurrentDateTime()}
            
            CRITICAL TOOL SELECTION RULES - FOLLOW THESE EXACTLY:
            
            ===== FOR CURRENT/REAL-TIME WEATHER =====
            Use getCurrentWeather tool when user asks for:
            - "now", "current", "currently", "today", "right now", "at present"
            - "现在", "当前", "今天", "目前", "此刻"
            - "How's the weather in [city]?" (without time specification)
            - "What's the weather like in [city]?" (without time specification)
            - "[city] weather" (without time specification)
            
            Examples:
            ✅ "Hong Kong weather now" → getCurrentWeather
            ✅ "Current weather in Tokyo" → getCurrentWeather
            ✅ "北京现在天气怎么样" → getCurrentWeather
            ✅ "What's the weather like in London?" → getCurrentWeather
            
            ===== FOR FUTURE/FORECAST WEATHER =====
            Use getWeatherForecast tool when user asks for:
            - "tomorrow", "next", "later", "forecast", "future", "will be"
            - "明天", "后天", "未来", "预报", "以后", "接下来"，"几天", "下周", "预测"
            - "day after tomorrow", "this week", "next week"
            - ANY time reference to future dates
            
            Examples:
            ✅ "Hong Kong weather tomorrow" → getWeatherForecast
            ✅ "How's the weather in New York tomorrow?" → getWeatherForecast
            ✅ "明天纽约天气如何" → getWeatherForecast
            ✅ "Tokyo weather next week" → getWeatherForecast
            
            ===== FOR MULTIPLE CITIES =====
            Use getMultipleWeather tool when user asks for:
            - Weather in multiple cities mentioned in one query
            - Comma-separated city names
            
            Examples:
            ✅ "Tokyo,Paris,London weather" → getMultipleWeather
            ✅ "Weather in Beijing and Shanghai" → getMultipleWeather
            
            ===== DECISION PROCESS =====
            1. First, identify if the query mentions time:
               - Future time words → getWeatherForecast
               - Current time words → getCurrentWeather
               - No time words → getCurrentWeather (default to current)
            
            2. Check if multiple cities are mentioned:
               - Multiple cities → getMultipleWeather
               - Single city → getCurrentWeather or getWeatherForecast
            
            3. Always extract the city name(s) clearly
            
            ===== RESPONSE FORMAT =====
            - Return the weather information directly as received from tools
            - Don't add JSON wrappers or "successful" status
            - The tools already provide beautifully formatted responses
            - If there's an error, provide a friendly error message
            
            REMEMBER: When in doubt about time, default to getCurrentWeather unless there's a clear future time reference.
            
            Always provide accurate, beautifully formatted weather information.
        """.trimIndent(),
        llmModel = OpenAIModels.Chat.GPT4o,
        toolRegistry = toolRegistry
    )
}