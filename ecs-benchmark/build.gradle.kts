plugins {
    alias(libs.plugins.jmh)
}

dependencies {
    implementation(project(":ecs-core"))
    jmh(libs.jmh.core)
    jmh(libs.jmh.annprocess)
}

jmh {
    warmupIterations.set(3)
    iterations.set(5)
    fork.set(2)
    jvmArgs.addAll("--enable-preview")
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
