plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
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

dependencies {
    // KSP API:版本与 root libs.versions.toml 中 ksp 对齐
    implementation("com.google.devtools.ksp:symbol-processing-api:${libs.versions.ksp.get()}")
    // 复用注解模块的 FQN 常量;不会被打入处理器产物
    compileOnly(project(":aiagent:lib_ai_annotations"))

    testImplementation(libs.junit)
}
