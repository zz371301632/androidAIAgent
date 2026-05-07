plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.vanniktech.publish)
}

// 纯 KSP 处理器:编译期跑,不会进任何 APK / JAR 产物。
// 业务模块通过 `ksp(project(":aiagent:lib_ai_compiler"))` 引入,不会污染 release 包。

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

mavenPublishing {
    coordinates(
        groupId    = "io.github.zz371301632",
        artifactId = "ai-agent-compiler",
        version    = providers.gradleProperty("VERSION_NAME").get(),
    )
    pom {
        name.set("AI Agent Compiler")
        description.set("KSP annotation processor for androidAIAgent SDK. Scans @AiTool / @AiSkill and generates boot registration code.")
    }
}

dependencies {
    // KSP API:版本与 root libs.versions.toml 中 ksp 对齐
    implementation("com.google.devtools.ksp:symbol-processing-api:${libs.versions.ksp.get()}")
    // 复用注解模块的 FQN 常量;不会被打入处理器产物
    compileOnly(project(":aiagent:lib_ai_annotations"))

    testImplementation(libs.junit)
}
