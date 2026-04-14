plugins {
    alias(libs.plugins.jmh)
}

dependencies {
    implementation(libs.dominion)
    jmh(libs.jmh.core)
    jmh(libs.jmh.annprocess)
}

jmh {
    warmupIterations.set(providers.gradleProperty("jmh.wi").map { it.toInt() }.orElse(3))
    iterations.set(providers.gradleProperty("jmh.i").map { it.toInt() }.orElse(5))
    fork.set(providers.gradleProperty("jmh.f").map { it.toInt() }.orElse(2))
    jvmArgs.addAll("--enable-preview")
    includes.addAll(providers.gradleProperty("jmh.includes").map { listOf(it) }.orElse(emptyList()))
    resultFormat.set("JSON")
    profilers.add("gc")
}
