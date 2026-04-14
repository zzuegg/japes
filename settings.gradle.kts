rootProject.name = "japes"

include("ecs-core")

// Benchmark modules live under benchmark/ — each targets one ECS
// implementation so comparisons stay self-contained.
include("benchmark:ecs-benchmark")
include("benchmark:ecs-benchmark-valhalla")
include("benchmark:ecs-benchmark-zayes")
include("benchmark:ecs-benchmark-dominion")
include("benchmark:ecs-benchmark-artemis")

// Map the nested module names to their real directories so Gradle locates
// the build files.
project(":benchmark:ecs-benchmark").projectDir = file("benchmark/ecs-benchmark")
project(":benchmark:ecs-benchmark-valhalla").projectDir = file("benchmark/ecs-benchmark-valhalla")
project(":benchmark:ecs-benchmark-zayes").projectDir = file("benchmark/ecs-benchmark-zayes")
project(":benchmark:ecs-benchmark-dominion").projectDir = file("benchmark/ecs-benchmark-dominion")
project(":benchmark:ecs-benchmark-artemis").projectDir = file("benchmark/ecs-benchmark-artemis")

include("benchmark:ecs-benchmark-sync")
project(":benchmark:ecs-benchmark-sync").projectDir = file("benchmark/ecs-benchmark-sync")

include("plugins:persistence:h2")
project(":plugins:persistence:h2").projectDir = file("plugins/persistence/h2")
