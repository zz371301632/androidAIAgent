plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// 可选的 Compose 聊天 UI:接入方想要现成 AgentChatScreen 就 implementation 本模块,
// 想自己画就只 implementation lib_ai_agent_sdk。本模块只暴露 stateless screen + 一个
// 默认 ViewModel 范例,不绑定 Activity / Fragment 容器。

android {
    namespace = "com.aiagent.ui"
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

    buildFeatures {
        compose = true
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
}

dependencies {
    // 把 SDK 暴露给消费者:接入方依赖本模块即可拿到 AiAgentRuntime / Tool / AgentLoop
    api(project(":aiagent:lib_ai_agent_sdk"))

    // Compose 全栈 api 暴露,接入方依赖本模块就能直接 setContent {} 用我们的 Screen
    val composeBom = platform(libs.androidx.compose.bom)
    api(composeBom)
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.ui.tooling.preview)
    api(libs.androidx.compose.foundation)
    api(libs.androidx.compose.material3)

    // viewModel<>() Compose 助手 + ViewModel 基类 + viewModelScope
    api(libs.androidx.lifecycle.viewmodel.compose)
    api(libs.androidx.lifecycle.viewmodel.ktx)

    implementation(libs.kotlinx.coroutines.android)
}
