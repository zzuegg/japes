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
    warmupIterations.set(3)
    iterations.set(5)
    fork.set(2)
    jvmArgs.addAll("--enable-preview")
}
