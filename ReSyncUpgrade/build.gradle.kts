import org.gradle.api.tasks.JavaExec
import org.gradle.language.jvm.tasks.ProcessResources
import java.util.zip.ZipFile

plugins {
    `java-library`
    application
}

group = "restudio.resync"
version = providers.gradleProperty("releaseVersion").getOrElse("1.3.0")

repositories {
    mavenCentral()
}

dependencies {
    api(project(":ReSyncCore"))
    testImplementation("org.junit.jupiter:junit-jupiter:6.0.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.0.3")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

base {
    archivesName.set("resync-offline-upgrader")
}

application {
    mainClass.set("restudio.resync.upgrade.cli.OfflineUpgradeCli")
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "restudio.resync.upgrade.cli.OfflineUpgradeCli"
    }
}

val productionNodeDefinitions = project.layout.projectDirectory.dir("src/main/resources/nodes/migrated")
val authoredNodeDefinitions = rootProject.layout.projectDirectory.dir("src/main/resources/nodes")
val generatedSchema = layout.buildDirectory.file("generated-resources/flow-schema/restudio/resync/flow/migration/flow-graph-schema-v2.json")
val generateFlowGraphMigrationSchema = tasks.register<JavaExec>("generateFlowGraphMigrationSchema") {
    val sourceFiles = fileTree(productionNodeDefinitions) {
        include("**/*.json")
        exclude("**/_*.json")
    }
    val authoredSourceFiles = fileTree(authoredNodeDefinitions) {
        include("**/*.json")
        exclude("**/_*.json")
    }
    inputs.files(sourceFiles, authoredSourceFiles)
    outputs.file(generatedSchema)
    dependsOn(tasks.named("compileJava"))
    classpath = files(sourceSets.main.get().output.classesDirs, configurations.runtimeClasspath)
    mainClass.set("restudio.resync.upgrade.flow.FlowGraphMigrationSchemaGenerator")
    args(productionNodeDefinitions.asFile.absolutePath, authoredNodeDefinitions.asFile.absolutePath, generatedSchema.get().asFile.absolutePath)
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(generateFlowGraphMigrationSchema)
    exclude("nodes/migrated/**")
    from(generatedSchema) {
        into("restudio/resync/flow/migration")
    }
}

val verifyUpgradeJarEntries = tasks.register("verifyUpgradeJarEntries") {
    dependsOn(tasks.jar)
    doLast {
        val archive = tasks.jar.get().archiveFile.get().asFile
        ZipFile(archive).use { zip ->
            check(zip.entries().asSequence().none { it.name.startsWith("nodes/") && !it.name.endsWith("/") }) {
                "Upgrade jar must not contain raw node definitions"
            }
            val schema = zip.getEntry("restudio/resync/flow/migration/flow-graph-schema-v2.json")
            check(schema != null) {
                "Upgrade jar is missing the generated FlowGraph migration schema"
            }
            val schemaText = zip.getInputStream(schema).bufferedReader().use { it.readText() }
            check(schemaText.contains("\"count\":60")) { "Upgrade schema source count is invalid" }
            check(schemaText.contains("\"root\":\"ReSyncUpgrade/src/main/resources/nodes/migrated\"")) {
                "Upgrade schema source provenance is invalid"
            }
            check(schemaText.contains("\"hash\":\"b8a58abcf644d99ef4e0cdacd9305ca87e4d32cf27883ec629e78f527d1c0a90\"")) {
                "Upgrade schema source hash is invalid"
            }
            check(schemaText.contains("\"root\":\"src/main/resources/nodes\"")) {
                "Upgrade schema authored source provenance is invalid"
            }
            check(schemaText.contains("\"active\":{\"count\":80")) {
                "Upgrade schema authored source count is invalid"
            }
            check(schemaText.contains("\"hash\":\"98d72e4a9921f2888c1a1523736bbf709ea490c7a08592e0cf40e51655916899\"")) {
                "Upgrade schema authored source hash is invalid"
            }
            check(schemaText.contains("\"sourcePinId\":\"mapA\"")) {
                "Upgrade schema authored pin migration is missing"
            }
            check(schemaText.contains("\"schemaHash\":\"226cda087ea07710d3d02a88ee6c3d7ebf91aa2f204c293ea8a02df30706a0df\"")) {
                "Upgrade schema artifact hash is invalid"
            }
        }
    }
}

tasks.test {
    useJUnitPlatform()
}

val exportGate3BSanitizedFixture = tasks.register<JavaExec>("exportGate3BSanitizedFixture") {
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("restudio.resync.upgrade.fixture.Gate3BSanitizedFixtureExporter")
    doFirst {
        val fixture = project.findProperty("gate3bFixture")?.toString()
        val output = project.findProperty("gate3bOutput")?.toString()
        if (fixture != null) {
            args("--fixture", fixture)
        }
        if (output != null) {
            args("--output", output)
        }
    }
}
