plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.vanniktech.publish)
}

// 纯 JVM 模块:任何项目(无论是否 Android、是否 debug-only)都可以
// `implementation(project(":aiagent:lib_ai_annotations"))` 来声明 AI 能力。
// 不引入 Android、不引入 okhttp、不引入协程,保持极薄。

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// 与 Java 编译目标对齐,避免 'compileJava'(17) 和 'compileKotlin' JVM 版本冲突
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

mavenPublishing {
    coordinates(
        groupId   = "io.github.zz371301632",
        artifactId = "ai-agent-annotations",
        version   = providers.gradleProperty("VERSION_NAME").get(),
    )
    pom {
        name.set("AI Agent Annotations")
        description.set("Annotation contracts (@AiTool / @AiSkill) for androidAIAgent SDK.")
    }
}

dependencies {
    // org.json 在 Android 平台自带,在 JVM 单测里走 mavenCentral 的 json jar。
    // compileOnly:Tool#execute 的 args 类型是 JSONObject,但本模块自身不需要把它打进产物。
    compileOnly(libs.json)

    testImplementation(libs.junit)
    testImplementation(libs.json)
}
