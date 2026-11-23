plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    application
}

group = "pipeline"
version = "0.1.0"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-slf4j:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    implementation("com.github.ajalt.clikt:clikt:4.3.0")
    implementation("com.github.javaparser:javaparser-core:3.28.2")
    implementation("org.xerial:sqlite-jdbc:3.45.1.0")
    implementation("com.charleskorn.kaml:kaml:0.56.0")

    implementation("ch.qos.logback:logback-classic:1.5.3")
    implementation("net.logstash.logback:logstash-logback-encoder:7.4")
    implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")

    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
}

application {
    applicationName = "pipeline"
    mainClass.set("pipeline.MainKt")
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "1g"
}

// Gradle fixture tests: run the real checker.init.gradle with Gradle TestKit on
// small generated projects (src/fixtureTest). They take about a minute, so they
// are a separate suite that `check` runs and `test` does not.
testing {
    suites {
        register<JvmTestSuite>("fixtureTest") {
            useJUnitJupiter("5.10.2")
            dependencies {
                implementation(project())
                implementation(gradleTestKit())
                implementation("org.jetbrains.kotlin:kotlin-test-junit5:2.4.21")
            }
            targets.all {
                testTask.configure {
                    maxHeapSize = "1g"
                    shouldRunAfter(tasks.test)
                    systemProperty(
                        "lltas.checkerFrameworkHome",
                        layout.projectDirectory.dir("vendor/checker-framework-3.42.0").asFile.absolutePath
                    )
                    systemProperty("lltas.testKitDir", layout.buildDirectory.dir("testkit").get().asFile.absolutePath)
                    systemProperty("lltas.gradleHome", gradle.gradleHomeDir!!.absolutePath)
                }
            }
        }
    }
}

// Lets the fixture tests use the harness's internal helpers, as `test` can.
kotlin.target.compilations.named("fixtureTest") {
    associateWith(kotlin.target.compilations.getByName("main"))
}

tasks.named("check") {
    dependsOn(testing.suites.named("fixtureTest"))
}
