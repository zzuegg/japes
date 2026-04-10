plugins {
    alias(libs.plugins.jmh)
}

val valhallaHome: String = providers.gradleProperty("valhalla.home")
    .orElse(providers.environmentVariable("VALHALLA_HOME"))
    .orElse("${System.getProperty("user.home")}/.sdkman/candidates/java/valhalla-ea")
    .get()

// javac flags for both user source and JMH-generated stubs — both need
// --enable-preview plus an --add-exports for jdk.internal.vm.annotation so
// @LooselyConsistentValue on benchmark value records compiles. That
// annotation is what JEP 401 EA actually requires to lay value records
// out flat in backing arrays; the Valhalla benchmarks use it, so the
// compile has to see the class.
val valhallaCompilerArgs = listOf(
    "--enable-preview",
    "--add-exports", "java.base/jdk.internal.vm.annotation=ALL-UNNAMED"
)

// Only the JMH source compile needs Valhalla (has value records)
tasks.named<JavaCompile>("compileJmhJava") {
    options.isFork = true
    options.forkOptions.javaHome = file(valhallaHome)
    options.compilerArgs.addAll(valhallaCompilerArgs)
    // Use Valhalla compiler for this task
    javaCompiler.set(javaToolchains.compilerFor {
        languageVersion.set(JavaLanguageVersion.of(27))
    })
}

// Generated JMH classes reference value type benchmarks, so also need Valhalla
tasks.named<JavaCompile>("jmhCompileGeneratedClasses") {
    options.isFork = true
    options.forkOptions.javaHome = file(valhallaHome)
    options.compilerArgs.addAll(valhallaCompilerArgs)
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
    jvmArgs.addAll(
        "--enable-preview",
        // Opens jdk.internal.value.ValueClass to DefaultComponentStorage's
        // reflection probe so it can call newNullRestrictedNonAtomicArray
        // for value-record component types and store them in a flat
        // primitive-backed array instead of a boxed reference array.
        "--add-exports", "java.base/jdk.internal.value=ALL-UNNAMED",
        // @LooselyConsistentValue lives here; benchmarks annotate their
        // value records with it to opt into the actual flat layout.
        "--add-exports", "java.base/jdk.internal.vm.annotation=ALL-UNNAMED"
    )
    jvm.set("$valhallaHome/bin/java")
}
