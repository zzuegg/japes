dependencies {
    implementation(project(":ecs-core"))
    implementation(libs.h2)
    testImplementation(libs.junit.api)
    testRuntimeOnly(libs.junit.engine)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
