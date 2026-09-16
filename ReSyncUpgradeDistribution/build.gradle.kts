import org.gradle.api.tasks.bundling.Zip

plugins {
    application
}

group = "restudio.resync"
version = providers.gradleProperty("releaseVersion").getOrElse("1.3.0")

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":ReSyncUpgrade"))
    runtimeOnly(project(":ReSyncUpgradeSqlite"))
    testImplementation("org.junit.jupiter:junit-jupiter:6.0.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.0.3")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

base {
    archivesName.set("resync-offline-upgrader-distribution")
}

application {
    mainClass.set("restudio.resync.upgrade.distribution.OfflineUpgradeDistribution")
}

val upstreamSchemaTask = project(":ReSyncUpgrade").tasks.named("generateFlowGraphMigrationSchema")
val distributionZip = tasks.named<Zip>("distZip").flatMap { it.archiveFile }

listOf("distZip", "distTar", "installDist").forEach { taskName ->
    tasks.named(taskName) {
        dependsOn(upstreamSchemaTask)
    }
}

tasks.test {
    useJUnitPlatform()
    dependsOn(tasks.named("distZip"))
    inputs.file(distributionZip)
    doFirst {
        systemProperty("resync.offline.upgrader.distribution.zip", distributionZip.get().asFile.absolutePath)
    }
}
