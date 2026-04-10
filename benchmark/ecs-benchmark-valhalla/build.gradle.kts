plugins {
    alias(libs.plugins.jmh)
}

val valhallaHome: String = providers.gradleProperty("valhalla.home")
    .orElse(providers.environmentVariable("VALHALLA_HOME"))
    .orElse("${System.getProperty("user.home")}/.sdkman/candidates/java/valhalla-ea")
    .get()

// Only the JMH source compile needs Valhalla (has value records)
tasks.named<JavaCompile>("compileJmhJava") {
    options.isFork = true
    options.forkOptions.javaHome = file(valhallaHome)
    options.compilerArgs.addAll(listOf("--enable-preview"))
    // Use Valhalla compiler for this task
    javaCompiler.set(javaToolchains.compilerFor {
        languageVersion.set(JavaLanguageVersion.of(27))
    })
}

// Generated JMH classes reference value type benchmarks, so also need Valhalla
tasks.named<JavaCompile>("jmhCompileGeneratedClasses") {
    options.isFork = true
    options.forkOptions.javaHome = file(valhallaHome)
    options.compilerArgs.addAll(listOf("--enable-preview"))
    javaCompiler.set(javaToolchains.compilerFor {
        languageVersion.set(JavaLanguageVersion.of(27))
    })
}

tasks.withType<JavaExec> {
    setExecutable("$valhallaHome/bin/java")
    jvmArgs("--enable-preview")
}

tasks.named<me.champeau.jmh.JmhBytecodeGeneratorTask>("jmhRunBytecodeGenerator") {
    jvmArgs.addAll(listOf("--enable-preview"))
    javaLauncher.set(provider {
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(27))
        }.get()
    })
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
    jvm.set("$valhallaHome/bin/java")
}
