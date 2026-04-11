---
title: Installation
---

# Installation

japes runs on **JDK 26** with `--enable-preview` enabled. The `ecs-core` module uses the `java.lang.classfile` API (JEP 484) for its tier-1 bytecode generator, which is a preview feature in JDK 26. There is no workaround — the toolchain is non-negotiable.

!!! tip "If you're evaluating"

    If you just want to run the benchmarks and poke at the code without setting up a consumer project, clone [github.com/zzuegg/japes](https://github.com/zzuegg/japes) directly and skip to [Quick start](quick-start.md). The dependency setup below is only needed when pulling japes into your own project.

## JDK toolchain

Install JDK 26 (Temurin, Oracle, any distribution). The Gradle toolchain resolver can do this automatically:

```kotlin title="build.gradle.kts"
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(26))
    }
}
```

Or verify manually:

```bash
$ java -version
openjdk version "26" ...
```

## Dependency from GitHub Packages

japes publishes SNAPSHOT Maven artifacts to [GitHub Packages](https://github.com/zzuegg/japes/packages) on every push to `main`. Consumers need two things:

1. A `maven { url = ... }` block pointing at `https://maven.pkg.github.com/zzuegg/japes`
2. A GitHub personal access token with `read:packages` scope, passed as environment variable or Gradle property

### Step 1 — authenticate

GitHub Packages requires authentication even for public reads. Create a classic personal access token at [github.com/settings/tokens](https://github.com/settings/tokens) with only the `read:packages` scope. Store it somewhere Gradle can read, e.g.:

=== "Environment variables"

    ```bash
    export GITHUB_ACTOR="your-github-username"
    export GITHUB_TOKEN="ghp_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
    ```

    Add both to your shell profile so every build has access.

=== "`~/.gradle/gradle.properties`"

    ```properties
    gpr.user=your-github-username
    gpr.key=ghp_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
    ```

    Kept outside your project's source tree so it doesn't accidentally end up on GitHub.

### Step 2 — add the repository

```kotlin title="build.gradle.kts"
repositories {
    mavenCentral()
    maven {
        name = "japes-github-packages"
        url = uri("https://maven.pkg.github.com/zzuegg/japes")
        credentials {
            username = System.getenv("GITHUB_ACTOR")
                ?: providers.gradleProperty("gpr.user").orNull
            password = System.getenv("GITHUB_TOKEN")
                ?: providers.gradleProperty("gpr.key").orNull
        }
    }
}
```

### Step 3 — declare the dependency

```kotlin title="build.gradle.kts"
dependencies {
    implementation("io.github.zzuegg.japes:ecs-core:0.1.0-SNAPSHOT")
}
```

### Step 4 — enable preview features

```kotlin title="build.gradle.kts"
tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(listOf("--enable-preview"))
}

tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs("--enable-preview")
}

tasks.withType<JavaExec> {
    jvmArgs("--enable-preview")
}
```

That's everything. Refresh your Gradle project and you should be able to `import zzuegg.ecs.world.World;`.

!!! warning "SNAPSHOT versioning"

    Until `1.0` ships, japes publishes SNAPSHOT artifacts from every commit to `main`. The `0.1.0-SNAPSHOT` coordinate is a moving target — Gradle caches snapshot downloads for 24 hours by default. Force a refresh with `--refresh-dependencies` if you need the very latest:

    ```bash
    ./gradlew build --refresh-dependencies
    ```

## Maven

If your project uses Maven instead of Gradle, the equivalent is:

```xml title="pom.xml"
<repositories>
    <repository>
        <id>japes-github-packages</id>
        <url>https://maven.pkg.github.com/zzuegg/japes</url>
    </repository>
</repositories>

<dependencies>
    <dependency>
        <groupId>io.github.zzuegg.japes</groupId>
        <artifactId>ecs-core</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </dependency>
</dependencies>
```

And in `~/.m2/settings.xml`:

```xml
<servers>
    <server>
        <id>japes-github-packages</id>
        <username>your-github-username</username>
        <password>ghp_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx</password>
    </server>
</servers>
```

Plus the compiler plugin flags:

```xml title="pom.xml"
<build>
    <plugins>
        <plugin>
            <artifactId>maven-compiler-plugin</artifactId>
            <configuration>
                <release>26</release>
                <compilerArgs>
                    <arg>--enable-preview</arg>
                </compilerArgs>
            </configuration>
        </plugin>
        <plugin>
            <artifactId>maven-surefire-plugin</artifactId>
            <configuration>
                <argLine>--enable-preview</argLine>
            </configuration>
        </plugin>
    </plugins>
</build>
```

## Building from source

If you'd rather depend on a local build (e.g. to pin a specific commit or test a pre-PR change):

```bash
git clone https://github.com/zzuegg/japes.git
cd japes
./gradlew :ecs-core:publishToMavenLocal
```

Then in your consumer project:

```kotlin title="build.gradle.kts"
repositories {
    mavenLocal()
}

dependencies {
    implementation("io.github.zzuegg.japes:ecs-core:0.1.0-SNAPSHOT")
}
```

## What's next

With japes on the classpath, head to [Quick start](quick-start.md) for the minimum runnable program, or jump straight into the [Tutorials](../tutorials/index.md) if you prefer learning topic-by-topic.
