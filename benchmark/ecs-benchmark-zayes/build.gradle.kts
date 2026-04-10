plugins {
    alias(libs.plugins.jmh)
}

dependencies {
    implementation("com.simsilica:zay-es:1.6.0")
    jmh(libs.jmh.core)
    jmh(libs.jmh.annprocess)
}
