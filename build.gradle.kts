plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
    jacoco
}

// Aggregated coverage over the app's brain: the pure model module plus the parts of :app a JVM
// test can reach (entity mapping, screen-state builders, formatting, the update decision table).
// Compose, Room-generated and Android-bound code stays out of the metric — counting what no JVM
// test can execute would make the number meaningless.
val coverageModules = listOf(":core-model")

tasks.register<JacocoReport>("jacocoAggregatedReport") {
    group = "verification"
    dependsOn(coverageModules.map { "$it:test" } + ":app:testDebugUnitTest")
    val projects = coverageModules.map { project(it) }
    val app = project(":app")
    executionData.setFrom(
        projects.map { it.layout.buildDirectory.file("jacoco/test.exec") } +
            app.layout.buildDirectory.file("outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec"),
    )
    sourceDirectories.setFrom(
        projects.map { it.layout.projectDirectory.dir("src/main/kotlin") } +
            app.layout.projectDirectory.dir("src/main/kotlin"),
    )
    classDirectories.setFrom(
        projects.map { it.layout.buildDirectory.dir("classes/kotlin/main") } +
            app.layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes").map { dir ->
                dir.asFileTree.matching {
                    include(
                        "dev/rwilco/data/ReminderEntity*",
                        "dev/rwilco/update/UpdateInfo*",
                        "dev/rwilco/vault/VaultSnapshot*",
                        "dev/rwilco/vault/VaultCrypto*",
                        "dev/rwilco/vault/VaultMigrations*",
                        "dev/rwilco/vault/VaultStep*",
                        "dev/rwilco/vault/VaultBackup*",
                        "dev/rwilco/ui/format/*",
                        "dev/rwilco/ui/home/HomeState*",
                        "dev/rwilco/ui/editor/EditorState*",
                        "dev/rwilco/ui/components/calendar/MonthGrid*",
                    )
                }
            },
    )
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(true)
    }
}
