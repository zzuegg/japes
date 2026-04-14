plugins {
    alias(libs.plugins.jmh)
}

dependencies {
    implementation("com.simsilica:zay-es:1.6.0")
    jmh(libs.jmh.core)
    jmh(libs.jmh.annprocess)
}

val fast = providers.gradleProperty("jmh.fast").isPresent
jmh {
    warmupIterations.set(providers.gradleProperty("jmh.wi").map { it.toInt() }.orElse(if (fast) 1 else 3))
    iterations.set(providers.gradleProperty("jmh.i").map { it.toInt() }.orElse(if (fast) 2 else 5))
    fork.set(providers.gradleProperty("jmh.f").map { it.toInt() }.orElse(if (fast) 1 else 2))
    jvmArgs.addAll("--enable-preview")
    includes.addAll(providers.gradleProperty("jmh.includes").map { listOf(it) }.orElse(emptyList()))
    resultFormat.set("JSON")
    profilers.add("gc")
}
