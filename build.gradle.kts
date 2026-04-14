@file:Suppress("UNCHECKED_CAST")

import java.util.Date

plugins {
    java
}

// Shared group + version for any subproject that publishes an artifact.
allprojects {
    group = "io.github.zzuegg.japes"
    version = providers.gradleProperty("japes.version").getOrElse("0.1.0-SNAPSHOT")
}

// ---------------------------------------------------------------------------
// Benchmark orchestration
// ---------------------------------------------------------------------------

val benchmarkModules = listOf(
    "benchmark:ecs-benchmark",
    "benchmark:ecs-benchmark-zayes",
    "benchmark:ecs-benchmark-dominion",
    "benchmark:ecs-benchmark-artemis",
    "benchmark:ecs-benchmark-sync",
)

val implMap = mapOf(
    "ecs-benchmark" to "japes",
    "ecs-benchmark-zayes" to "zayes",
    "ecs-benchmark-artemis" to "artemis",
    "ecs-benchmark-dominion" to "dominion",
    "ecs-benchmark-sync" to "japes-sync",
    "ecs-benchmark-valhalla" to "japes-valhalla",
)

// Run Bevy (Rust/Criterion) benchmarks.
val bevyBenchmark = tasks.register<Exec>("bevyBenchmark") {
    description = "Run Bevy ECS Criterion benchmarks"
    group = "benchmark"
    workingDir = file("${rootDir}/benchmark/bevy-benchmark")
    commandLine("cargo", "bench")
}

// ---------------------------------------------------------------------------
// mergeResults: JMH JSON + Criterion → site/docs/data/benchmark-results.json
// ---------------------------------------------------------------------------
val mergeResults = tasks.register("mergeResults") {
    description = "Merge JMH + Criterion results into site/docs/data/benchmark-results.json"
    group = "benchmark"
    doLast {
        val parser = groovy.json.JsonSlurper()
        val allResults = mutableListOf<Map<String, Any?>>()

        // Process JMH modules
        val benchDir = file("benchmark")
        for (modDir in (benchDir.listFiles() ?: emptyArray<File>()).sortedBy { it.name }) {
            if (!modDir.name.startsWith("ecs-benchmark")) continue
            val resultsFile = modDir.resolve("build/results/jmh/results.json")
            if (!resultsFile.exists()) { println("  SKIP ${modDir.name}: no results.json"); continue }
            val impl = implMap[modDir.name] ?: modDir.name
            val entries = parser.parse(resultsFile) as List<Map<String, Any?>>
            var count = 0
            for (entry in entries) {
                val benchmark = entry["benchmark"] as? String ?: continue
                if (":gc." in benchmark) continue
                val mode = entry["mode"] as? String ?: ""
                val primary = entry["primaryMetric"] as? Map<String, Any?> ?: continue
                val score = (primary["score"] as? Number)?.toDouble() ?: 0.0
                val scoreError = (primary["scoreError"] as? Number)?.toDouble()
                val scoreUnit = primary["scoreUnit"] as? String ?: ""
                val params = entry["params"] as? Map<String, String> ?: emptyMap()

                val us = jmhToUs(score, scoreUnit, mode)
                val ops = jmhToOpsMs(score, scoreUnit, mode)
                val alloc = extractGcAlloc(entry)
                val ec = entityCount(params)
                val eps = if (ops != null && ec != null && ops > 0) (ops * 1000.0 * ec).toLong() else null

                allResults.add(mapOf(
                    "benchmark" to benchmark,
                    "name" to benchmark.substringAfterLast("."),
                    "implementation" to impl,
                    "params" to params,
                    "mode" to mode,
                    "us_per_op" to us?.let { "%.3f".format(it).toDouble() },
                    "ops_per_ms" to ops?.let { "%.3f".format(it).toDouble() },
                    "entities_per_second" to eps,
                    "bytes_per_op" to alloc?.let { "%.1f".format(it).toDouble() },
                    "score" to "%.6f".format(score).toDouble(),
                    "score_unit" to scoreUnit,
                    "score_error" to scoreError?.let { if (it.isNaN()) null else "%.6f".format(it).toDouble() },
                ))
                count++
            }
            println("  ${modDir.name}: $count benchmarks")
        }

        // Process Criterion (Bevy)
        val criterionDir = file("benchmark/bevy-benchmark/target/criterion")
        if (criterionDir.isDirectory) {
            var count = 0
            criterionDir.walkTopDown()
                .filter { it.name == "estimates.json" && it.parentFile.name == "new" }
                .sorted()
                .forEach { estFile ->
                    val benchPath = estFile.parentFile.parentFile.toRelativeString(criterionDir)
                    val data = parser.parse(estFile) as Map<String, Any?>
                    val meanNs = ((data["mean"] as? Map<*, *>)?.get("point_estimate") as? Number)?.toDouble() ?: 0.0
                    val stdErr = ((data["mean"] as? Map<*, *>)?.get("standard_error") as? Number)?.toDouble() ?: 0.0

                    val us = meanNs / 1000.0
                    val ops = if (meanNs > 0) 1_000_000.0 / meanNs else null
                    val params = criterionParams(benchPath)
                    val ec = entityCount(params)
                    val eps = if (ops != null && ec != null && ops > 0) (ops * 1000.0 * ec).toLong() else null

                    allResults.add(mapOf(
                        "benchmark" to "bevy.${benchPath.replace("/", ".")}",
                        "name" to criterionName(benchPath),
                        "implementation" to "bevy",
                        "params" to params,
                        "mode" to "avgt",
                        "us_per_op" to "%.3f".format(us).toDouble(),
                        "ops_per_ms" to ops?.let { "%.3f".format(it).toDouble() },
                        "entities_per_second" to eps,
                        "bytes_per_op" to null,
                        "score" to "%.3f".format(meanNs).toDouble(),
                        "score_unit" to "ns/op",
                        "score_error" to "%.3f".format(stdErr).toDouble(),
                    ))
                    count++
                }
            if (count > 0) println("  bevy-benchmark: $count benchmarks")
        } else {
            println("  SKIP bevy-benchmark: no Criterion results")
        }

        if (allResults.isEmpty()) { println("No results found."); return@doLast }

        allResults.sortWith(compareBy({ it["implementation"] as? String }, { it["benchmark"] as? String }))

        val jdk = Runtime.getRuntime().exec(arrayOf("java", "-version")).errorStream
            .bufferedReader().readLine() ?: "unknown"
        val rust = try { Runtime.getRuntime().exec(arrayOf("rustc", "--version")).inputStream
            .bufferedReader().readLine() } catch (_: Exception) { "n/a" }

        val output = mapOf(
            "generated" to Date().toInstant().toString(),
            "jdk" to jdk,
            "rust" to (rust ?: "n/a"),
            "results" to allResults,
        )
        val outFile = file("site/docs/data/benchmark-results.json")
        outFile.parentFile.mkdirs()
        outFile.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(output)))
        println("\nWrote ${allResults.size} benchmarks to $outFile")
    }
}

// ---------------------------------------------------------------------------
// updateSiteTables: regenerate markdown benchmark tables from JSON
// ---------------------------------------------------------------------------
val updateSiteTables = tasks.register("updateSiteTables") {
    description = "Regenerate benchmark tables in site docs and README from benchmark-results.json"
    group = "benchmark"
    mustRunAfter(mergeResults)
    doLast {
        val jsonFile = file("site/docs/data/benchmark-results.json")
        if (!jsonFile.exists()) { println("No benchmark-results.json found"); return@doLast }
        val parser = groovy.json.JsonSlurper()
        val data = parser.parse(jsonFile) as Map<String, Any?>
        val results = data["results"] as List<Map<String, Any?>>

        fun lookup(impl: String, namePart: String, paramKey: String? = null, paramVal: String? = null): Map<String, Any?>? {
            return results.find { r ->
                r["implementation"] == impl &&
                (r["benchmark"] as? String)?.contains(namePart) == true &&
                (paramKey == null || (r["params"] as? Map<*, *>)?.get(paramKey) == paramVal)
            }
        }

        fun usStr(r: Map<String, Any?>?): String {
            val v = (r?.get("us_per_op") as? Number)?.toDouble() ?: return "—"
            return if (v < 1) "%.3f".format(v) else if (v < 100) "%.1f".format(v) else "%.0f".format(v)
        }

        fun bopStr(r: Map<String, Any?>?): String {
            val v = (r?.get("bytes_per_op") as? Number)?.toDouble() ?: return "—"
            return if (v < 1) "**0**" else "%,.0f".format(v)
        }

        // --- Update README.md ---
        val readme = file("README.md")
        if (readme.exists()) {
            fun ratio(j: Map<String, Any?>?, b: Map<String, Any?>?): String {
                val jus = (j?.get("us_per_op") as? Number)?.toDouble() ?: return "—"
                val bus = (b?.get("us_per_op") as? Number)?.toDouble() ?: return "—"
                val r = jus / bus
                return if (r < 1) "**%.2f× faster**".format(1.0 / r) else "%.1f× slower".format(r)
            }

            val jIW = lookup("japes", "iterateWithWrite", "entityCount", "10000")
            val bIW = lookup("bevy", "read_write", "entityCount", "10000")
            val aIW = lookup("artemis", "iterateWithWrite", "entityCount", "10000")
            val jNB = lookup("japes", "simulateOneTick", "bodyCount", "10000")
            val bNB = lookup("bevy", "one_tick", "entityCount", "10000")
            val aNB = lookup("artemis", "simulateOneTick", "bodyCount", "10000")
            val jSD = lookup("japes", "SparseDeltaBenchmark", "entityCount", "10000")
            val bSD = lookup("bevy", "sparse_delta", "entityCount", "10000")
            val aSD = lookup("artemis", "SparseDelta", "entityCount", "10000")
            val jRT10k = lookup("japes", "RealisticTickBenchmark", "entityCount", "10000")
            val bRT10k = lookup("bevy", "realistic_tick", "entityCount", "10000")
            val aRT10k = lookup("artemis", "RealisticTick", "entityCount", "10000")
            val jRT100k = lookup("japes", "RealisticTickBenchmark", "entityCount", "100000")
            val bRT100k = lookup("bevy", "realistic_tick", "entityCount", "100000")
            val aRT100k = lookup("artemis", "RealisticTick", "entityCount", "100000")
            val jPS = lookup("japes", "ParticleScenario", "entityCount", "10000")
            val bPS = lookup("bevy", "particle_tick", "entityCount", "10000")
            val aPS = lookup("artemis", "ParticleScenario", "entityCount", "10000")
            val jPP = lookup("japes", "PredatorPreyForEachPairBenchmark")
            val bPPopt = lookup("bevy", "optimized_tick", "predators", "500")

            val table = """
## Benchmark snapshot (µs/op)

| Benchmark | **japes** | Bevy (Rust) | Artemis | vs Bevy |
|---|---:|---:|---:|---|
| iterateWithWrite 10k | **${usStr(jIW)}** | ${usStr(bIW)} | ${usStr(aIW)} | ${ratio(jIW, bIW)} |
| NBody 10k | **${usStr(jNB)}** | ${usStr(bNB)} | ${usStr(aNB)} | ${ratio(jNB, bNB)} |
| SparseDelta 10k | **${usStr(jSD)}** | ${usStr(bSD)} | ${usStr(aSD)} | ${ratio(jSD, bSD)} |
| RealisticTick 10k (3 observers) | **${usStr(jRT10k)}** | ${usStr(bRT10k)} | ${usStr(aRT10k)} | ${ratio(jRT10k, bRT10k)} |
| RealisticTick 100k | **${usStr(jRT100k)}** | ${usStr(bRT100k)} | ${usStr(aRT100k)} | ${ratio(jRT100k, bRT100k)} |
| ParticleScenario 10k | **${usStr(jPS)}** | ${usStr(bPS)} | ${usStr(aPS)} | ${ratio(jPS, bPS)} |
| PredatorPrey @ForEachPair 500×2000 | **${usStr(jPP)}** | ${usStr(bPPopt)} | — | ${ratio(jPP, bPPopt)} |

### Allocation per tick (japes, B/op)

| Benchmark | B/op |
|---|---:|
| iterateWithWrite 10k | ${bopStr(jIW)} |
| NBody 10k | ${bopStr(jNB)} |
| SparseDelta 10k | ${bopStr(jSD)} |
| RealisticTick 10k | ${bopStr(jRT10k)} |
| ParticleScenario 10k | ${bopStr(jPS)} |

**[Full results and methodology](https://zzuegg.github.io/japes/benchmarks/)**

""".trimStart()

            val text = readme.readText()
            val start = text.indexOf("## Benchmark snapshot")
            val end = text.indexOf("\n## ", start + 1)
            if (start >= 0 && end >= 0) {
                val updated = text.substring(0, start) + table + text.substring(end + 1)
                readme.writeText(updated)
                println("Updated README.md benchmark table")
            }
        }

        println("Site tables updated from benchmark-results.json")
    }
}

// Run all benchmark suites, merge results, update site tables.
// Usage: ./gradlew benchmarkAll
//   Fast mode: ./gradlew benchmarkAll -Pjmh.fast
tasks.register("benchmarkAll") {
    description = "Run all benchmarks, merge results, and update site tables"
    group = "benchmark"
    for (mod in benchmarkModules) { dependsOn("$mod:jmh") }
    dependsOn(bevyBenchmark)
    finalizedBy(mergeResults, updateSiteTables)
}

// Force sequential JMH execution to prevent lock conflicts.
gradle.projectsEvaluated {
    val jmhTasks = benchmarkModules.mapNotNull { tasks.findByPath("$it:jmh") }
    for (i in 1 until jmhTasks.size) {
        jmhTasks[i].mustRunAfter(jmhTasks[i - 1])
    }
}

// ---------------------------------------------------------------------------
// Helper functions for benchmark data processing
// ---------------------------------------------------------------------------

fun jmhToUs(score: Double, unit: String, mode: String): Double? = when (mode) {
    "thrpt" -> when {
        "ms" in unit -> if (score > 0) 1000.0 / score else null
        "s" in unit -> if (score > 0) 1_000_000.0 / score else null
        else -> null
    }
    "avgt", "sample", "ss" -> when {
        "us" in unit -> score
        "ms" in unit -> score * 1000.0
        "ns" in unit -> score / 1000.0
        "s" in unit -> score * 1_000_000.0
        else -> score
    }
    else -> null
}

fun jmhToOpsMs(score: Double, unit: String, mode: String): Double? = when (mode) {
    "thrpt" -> when {
        "ms" in unit -> score
        "s" in unit -> score / 1000.0
        else -> score
    }
    "avgt", "sample", "ss" -> when {
        "us" in unit -> if (score > 0) 1000.0 / score else null
        "ms" in unit -> if (score > 0) 1.0 / score else null
        "ns" in unit -> if (score > 0) 1_000_000.0 / score else null
        else -> null
    }
    else -> null
}

fun extractGcAlloc(entry: Map<String, Any?>): Double? {
    val secondary = entry["secondaryMetrics"] as? Map<String, Any?> ?: return null
    val gc = secondary["gc.alloc.rate.norm"] as? Map<String, Any?> ?: return null
    return (gc["score"] as? Number)?.toDouble()
}

fun entityCount(params: Map<String, String>): Long? {
    for (key in listOf("entityCount", "bodyCount")) {
        params[key]?.toLongOrNull()?.let { return it }
    }
    val pred = params["predators"]?.toLongOrNull()
    val prey = params["preyPerPredator"]?.toLongOrNull()
    if (pred != null && prey != null) return pred * prey
    return null
}

fun criterionParams(benchPath: String): Map<String, String> {
    val parts = benchPath.split("/")
    for (part in parts) {
        val m = Regex("pred_(\\d+)_prey_(\\d+)").find(part)
        if (m != null) return mapOf("predators" to m.groupValues[1], "preyPerPredator" to m.groupValues[2])
    }
    for (part in parts.reversed()) {
        if (part.all { it.isDigit() }) return mapOf("entityCount" to part)
    }
    return emptyMap()
}

fun criterionName(benchPath: String): String {
    return benchPath.split("/").filter { p ->
        !p.all { it.isDigit() } && !Regex("pred_\\d+_prey_\\d+").matches(p)
    }.joinToString("/")
}

// ---------------------------------------------------------------------------
// Subprojects
// ---------------------------------------------------------------------------

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
