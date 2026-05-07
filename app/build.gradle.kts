import java.net.URL
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// ─── Vosk 离线语音模型自动获取 ────────────────────────────────────────────────
// 把 vosk-model-small-cn-0.22.zip(~42MB)拉到 assets/。已存在则跳过 —— 幂等。
// 不进 git(.gitignore),clone 后首次 build 会触发下载。
val voskModelZipName = "vosk-model-small-cn-0.22.zip"
val voskModelUrl = "https://alphacephei.com/vosk/models/$voskModelZipName"
val voskModelAssetFile = file("src/main/assets/$voskModelZipName")
val downloadVoskModel by tasks.registering {
    description = "Download Vosk Chinese small model into assets/ if not present."
    group = "build setup"
    outputs.file(voskModelAssetFile)
    onlyIf { !voskModelAssetFile.exists() }
    doLast {
        voskModelAssetFile.parentFile.mkdirs()
        logger.lifecycle("Downloading $voskModelUrl → $voskModelAssetFile")
        URL(voskModelUrl).openStream().use { input ->
            voskModelAssetFile.outputStream().use { output -> input.copyTo(output) }
        }
        logger.lifecycle("Vosk model downloaded (${voskModelAssetFile.length() / 1024 / 1024} MB)")
    }
}
tasks.named("preBuild") { dependsOn(downloadVoskModel) }

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

    // Vosk 模型 zip 已经压缩过,aapt 不要再压一次 —— 否则装机解压更慢、APK 也压不下去
    androidResources {
        noCompress.add("zip")
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

    // setContent / enableEdgeToEdge:Compose 全栈 deps 由 lib_ai_agent_ui 通过 api 暴露,
    // 这里只补一个 Activity 与 Compose 的桥接(库模块没有 Activity 概念,不便 api 出去)
    implementation(libs.androidx.activity.compose)

    // AI Agent SDK + 现成的 Compose 聊天 UI;不想要 UI 的项目把下面这行删掉自己渲染即可
    implementation(project(":aiagent:lib_ai_agent_sdk"))
    implementation(project(":aiagent:lib_ai_agent_ui"))
    implementation(project(":aiagent:lib_ai_annotations"))
    kspDebug(project(":aiagent:lib_ai_compiler"))

    // SDK 公开 API 用到 JSONObject(Tool.execute 的入参),Android 平台自带 org.json
    implementation(libs.kotlinx.coroutines.android)

    // Vosk 离线 ASR(demo 唯一一处接入;不想要 voice 的项目删掉这两行 + DemoApp 注入即可)
    implementation(libs.vosk.android)
    implementation(libs.jna) { artifact { type = "aar" } }

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}