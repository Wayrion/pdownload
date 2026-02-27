plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    application
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
    testImplementation("io.kotest:kotest-assertions-core:5.9.1")
    testImplementation(libs.junit.jupiter.engine)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(24)
    }
}

layout.buildDirectory.set(rootProject.layout.buildDirectory.dir("lib"))

application {
    mainClass = "org.example.DownloaderCliKt"
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

tasks.register<JavaExec>("runBenchmark") {
    group = "application"
    description = "Runs parallel downloader benchmark matrix and writes JSON output"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "org.example.BenchmarkCliKt"
    workingDir = rootProject.projectDir
}
