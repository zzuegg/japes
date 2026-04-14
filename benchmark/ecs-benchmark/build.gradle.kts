plugins {
    alias(libs.plugins.jmh)
}

dependencies {
    implementation(project(":ecs-core"))
    jmh(libs.jmh.core)
    jmh(libs.jmh.annprocess)
}

jmh {
    warmupIterations.set(providers.gradleProperty("jmh.wi").map { it.toInt() }.orElse(3))
    iterations.set(providers.gradleProperty("jmh.i").map { it.toInt() }.orElse(5))
    fork.set(providers.gradleProperty("jmh.f").map { it.toInt() }.orElse(2))
    jvmArgs.addAll("--enable-preview")
    includes.addAll(providers.gradleProperty("jmh.includes").map { listOf(it) }.orElse(emptyList()))
    val prof = providers.gradleProperty("jmh.prof")
    if (prof.isPresent) profilers.add(prof)
    resultFormat.set("JSON")
    profilers.add("gc")
}

tasks.register<JavaExec>("jmhValhalla") {
    val valhallaHome = providers.gradleProperty("valhalla.home")
        .orElse(providers.environmentVariable("VALHALLA_HOME"))

    dependsOn("jmhJar")
    mainClass.set("org.openjdk.jmh.Main")
    classpath = files(tasks.named("jmhJar"))
    jvmArgs("--enable-preview")

    doFirst {
        val home = valhallaHome.orNull
            ?: throw GradleException("Set valhalla.home property or VALHALLA_HOME env var")
        setExecutable("$home/bin/java")
    }
}
