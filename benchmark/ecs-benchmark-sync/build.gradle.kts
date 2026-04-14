plugins {
    alias(libs.plugins.jmh)
}

dependencies {
    implementation(project(":ecs-core"))
    implementation(libs.h2)
    jmh(libs.jmh.core)
    jmh(libs.jmh.annprocess)
}

jmh {
    val inc = providers.gradleProperty("jmh.includes")
    if (inc.isPresent) includes.set(listOf(inc.get()))
    warmupIterations.set(providers.gradleProperty("jmh.wi").map { it.toInt() }.orElse(3))
    iterations.set(providers.gradleProperty("jmh.i").map { it.toInt() }.orElse(5))
    fork.set(providers.gradleProperty("jmh.f").map { it.toInt() }.orElse(2))
    val prof = providers.gradleProperty("jmh.prof")
    if (prof.isPresent) profilers.set(listOf(prof.get()))
    jvmArgs.addAll("--enable-preview")
}
