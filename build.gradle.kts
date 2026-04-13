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
