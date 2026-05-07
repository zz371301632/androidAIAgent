plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.vanniktech.publish)
}

// 项目无关的 AI Agent SDK。这里只声明:OkHttp + Kotlin 协程 + lib_ai_annotations(契约)。
// 任何 Android 项目可以直接抄走;保持瘦身,不引入 Compose / Navigation / Paging / Coil 等业务依赖。

android {
    namespace = "com.aiagent.sdk"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // 单测里默认不 mock android.* 的方法返回值,日志接口已被 holder 隔离,
    // SDK 内不再直接依赖 android.os.Process,这里仍开着保险一手。
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// 与 Java 编译目标对齐,避免 'compileJava'(17) 和 'compileKotlin' JVM 版本冲突
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Maven Central 强制要求每个 artifact 都带 javadoc.jar(可空);Kotlin 2.2 + AGP 自带的
// dokka 链路依赖 ASM8 而本工程编译产物含 PermittedSubclasses(ASM9),会让 javaDocReleaseGeneration
// 失败,所以这里禁用官方 javadoc 生成,改用一个空 jar 兜底,合规即可。
val emptyJavadocJar = tasks.register<Jar>("emptyJavadocJar") {
    archiveClassifier.set("javadoc")
}

mavenPublishing {
    configure(
        com.vanniktech.maven.publish.AndroidSingleVariantLibrary(
            variant = "release",
            sourcesJar = true,
            publishJavadocJar = false,
        )
    )
    coordinates(
        groupId    = "io.github.zz371301632",
        artifactId = "ai-agent-sdk",
        version    = providers.gradleProperty("VERSION_NAME").get(),
    )
    pom {
        name.set("AI Agent SDK")
        description.set("Android AI Agent SDK – core runtime: AgentLoop, LlmClient (OpenAI-compatible SSE), ToolRegistry, SkillRegistry.")
    }
}

afterEvaluate {
    publishing {
        publications.withType<MavenPublication>().configureEach {
            artifact(emptyJavadocJar)
        }
    }
}

dependencies {
    // 注解契约模块:Tool / ToolResult / ToolSchema / SkillManifest / AiCapabilityRegistry
    api(project(":aiagent:lib_ai_annotations"))

    // 协程:LlmClient.chatStream 返回 Flow
    implementation(libs.kotlinx.coroutines.android)

    // 网络:OpenAI 兼容协议、SSE 流
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.json)
    testImplementation(libs.okhttp.mockwebserver)
}
