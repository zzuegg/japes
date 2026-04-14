plugins {
    java
}

// Shared group + version for any subproject that publishes an artifact.
// The snapshot publish workflow uploads these to GitHub Packages on
// every push to main.
allprojects {
    group = "io.github.zzuegg.japes"
    version = providers.gradleProperty("japes.version").getOrElse("0.1.0-SNAPSHOT")
}

// ---------------------------------------------------------------------------
// Benchmark orchestration
// ---------------------------------------------------------------------------

// Merge JMH JSON results into site/data/benchmark-results.json.
val mergeResults = tasks.register<Exec>("mergeResults") {
    description = "Merge existing JMH JSON results into site/data/benchmark-results.json"
    group = "benchmark"
    commandLine("python3", "${rootDir}/benchmark/merge-results.py")
}

// Run all benchmark suites sequentially, then merge results into a single JSON.
// Usage: ./gradlew benchmarkAll
//   Override JMH params: ./gradlew benchmarkAll -Pjmh.wi=1 -Pjmh.i=3 -Pjmh.f=1
tasks.register("benchmarkAll") {
    description = "Run all JMH benchmark suites and merge results into site/data/benchmark-results.json"
    group = "benchmark"

    val modules = listOf(
        "benchmark:ecs-benchmark",
        "benchmark:ecs-benchmark-zayes",
        "benchmark:ecs-benchmark-dominion",
        "benchmark:ecs-benchmark-artemis",
        "benchmark:ecs-benchmark-sync",
    )
    for (mod in modules) {
        dependsOn("$mod:jmh")
    }
    finalizedBy(mergeResults)
}

subprojects {
    apply(plugin = "java")

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(26))
        }
    }

    tasks.withType<JavaCompile> {
        options.compilerArgs.addAll(listOf("--enable-preview"))
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        jvmArgs("--enable-preview", "-Dzzuegg.ecs.noWarmup=true")
    }

    tasks.withType<JavaExec> {
        jvmArgs("--enable-preview")
    }

    repositories {
        mavenCentral()
    }
}
