plugins {
    `maven-publish`
}

dependencies {
    testImplementation(libs.junit.api)
    testImplementation(libs.junit.params)
    testRuntimeOnly(libs.junit.engine)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Publish as a Maven artifact. The snapshot workflow
// (.github/workflows/publish-snapshot.yml) uploads this to
// GitHub Packages on every push to main.
java {
    withSourcesJar()
    withJavadocJar()
}

// Javadoc generation on preview-feature sources needs --enable-preview
// and a matching --source flag so javadoc accepts the preview syntax.
tasks.withType<Javadoc> {
    (options as StandardJavadocDocletOptions).apply {
        addBooleanOption("-enable-preview", true)
        addStringOption("source", "26")
        // Swallow warnings from unknown preview tags so the build stays green.
        addStringOption("Xdoclint:none", "-quiet")
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])

            artifactId = "ecs-core"

            pom {
                name.set("japes ecs-core")
                description.set(
                    "japes — Java Archetype-based Parallel Entity System. " +
                    "High-throughput ECS with first-class change detection, " +
                    "tier-1 bytecode generation and Flecs-style relations."
                )
                url.set("https://github.com/zzuegg/japes")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                developers {
                    developer {
                        id.set("zzuegg")
                        name.set("zzuegg")
                        url.set("https://github.com/zzuegg")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/zzuegg/japes.git")
                    developerConnection.set("scm:git:ssh://github.com/zzuegg/japes.git")
                    url.set("https://github.com/zzuegg/japes")
                }
            }
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/zzuegg/japes")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                    ?: providers.gradleProperty("gpr.user").orNull
                password = System.getenv("GITHUB_TOKEN")
                    ?: providers.gradleProperty("gpr.key").orNull
            }
        }
    }
}
