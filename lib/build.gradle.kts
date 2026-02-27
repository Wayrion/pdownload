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
    testRuntimeOnly("io.github.classgraph:classgraph:4.8.184")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(24)
    }
}

layout.buildDirectory.set(rootProject.layout.buildDirectory.dir("lib"))

application {
    mainClass = "com.wayrion.pdownload.DownloaderCliKt"
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    systemProperty("kotest.framework.classpath.scanning.autoscan.disable", "true")
}

tasks.register<JavaExec>("runBenchmark") {
    group = "application"
    description = "Runs parallel downloader benchmark matrix and writes JSON output"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "com.wayrion.pdownload.BenchmarkCliKt"
    workingDir = rootProject.projectDir
}
