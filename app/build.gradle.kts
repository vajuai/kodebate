plugins {
    // 与 koog-agents 对齐 Kotlin 版本
    kotlin("jvm") version "2.1.21"
    application
    kotlin("plugin.serialization") version "2.1.21"
    // 新增 Shadow 插件，用于创建可执行的 "胖" JAR
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven(url = "https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
}

// 定义与 koog-agents 兼容的依赖版本
val ktorVersion = "3.1.3"
val coroutinesVersion = "1.10.2"
val serializationVersion = "1.8.1"

dependencies {
    // --- 强制指定核心库版本以匹配 koog-agents ---
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")

    // 统一 Koog Agents 相关库的版本
    val koogVersion = "0.3.0"
    implementation("ai.koog:koog-agents:$koogVersion")
    implementation("ai.koog:prompt-executor-openai-client:$koogVersion")
    implementation("ai.koog:prompt-executor-llms-all:$koogVersion")

    // 日志记录器
    implementation("org.slf4j:slf4j-simple:2.0.17") // 对齐版本
    implementation("ch.qos.logback:logback-classic:1.5.13") // 对齐版本

    // 测试依赖
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // --- Ktor 3.x 依赖 ---
    // Ktor 3 不再需要 BOM，直接指定版本即可
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-sse:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
}

application {
    mainClass.set("org.example.DebateWebAppKt")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions.jvmTarget = "21"
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}