plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
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

    kotlinOptions {
        jvmTarget = "17"
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
