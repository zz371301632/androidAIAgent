import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// 从仓库根目录的 local.properties 读 AI 配置(不进 git)。完整可用键:
//   ai.provider=deepseek | custom                  # 选哪家,默认 deepseek
//   ai.deepseek.key / baseUrl / model              # provider=deepseek 时使用
//   ai.gateway.key  / baseUrl / model              # provider=custom   时使用,
//                                                  # 适合自部署 / 企业内 OpenAI 兼容
//                                                  # 网关(SDK 会自动加 trace_id 头 + rid 字段)
val aiProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val aiProvider: String = aiProps.getProperty("ai.provider", "deepseek")

val aiDeepseekKey: String = aiProps.getProperty("ai.deepseek.key", "")
val aiDeepseekBaseUrl: String = aiProps.getProperty("ai.deepseek.baseUrl", "https://api.deepseek.com")
val aiDeepseekModel: String = aiProps.getProperty("ai.deepseek.model", "deepseek-chat")

val aiGatewayKey: String = aiProps.getProperty("ai.gateway.key", "")
val aiGatewayBaseUrl: String = aiProps.getProperty("ai.gateway.baseUrl", "")
val aiGatewayModel: String = aiProps.getProperty("ai.gateway.model", "deepseek-chat")

android {
    namespace = "com.zhangz.androidaiagent"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zhangz.androidaiagent"
        minSdk = 24
        targetSdk = 33
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "AI_PROVIDER", "\"$aiProvider\"")

        buildConfigField("String", "AI_DEEPSEEK_KEY", "\"$aiDeepseekKey\"")
        buildConfigField("String", "AI_DEEPSEEK_BASE_URL", "\"$aiDeepseekBaseUrl\"")
        buildConfigField("String", "AI_DEEPSEEK_MODEL", "\"$aiDeepseekModel\"")

        buildConfigField("String", "AI_GATEWAY_KEY", "\"$aiGatewayKey\"")
        buildConfigField("String", "AI_GATEWAY_BASE_URL", "\"$aiGatewayBaseUrl\"")
        buildConfigField("String", "AI_GATEWAY_MODEL", "\"$aiGatewayModel\"")
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
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// KSP 给本模块的生成函数后缀:bootAiTools_app()
ksp {
    arg("aiagent.bootName", "app")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)

    // AI Agent SDK:运行时 + 注解契约;KSP 处理器只在 debug 包跑
    implementation(project(":aiagent:lib_ai_agent_sdk"))
    implementation(project(":aiagent:lib_ai_annotations"))
    kspDebug(project(":aiagent:lib_ai_compiler"))

    // SDK 公开 API 用到 JSONObject(Tool.execute 的入参),Android 平台自带 org.json
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}