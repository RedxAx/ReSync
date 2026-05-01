import groovy.json.JsonSlurper

plugins {
    id("java")
    id("com.gradleup.shadow") version "8.3.0"
    id("xyz.jpenilla.run-paper") version "2.3.0"
}

group = "restudio.resync"
version = "1.0.1"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://oss.sonatype.org/content/groups/public/")
    maven("https://jitpack.io")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    maven("https://repo.nexomc.com/releases")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21-R0.1-SNAPSHOT")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1")
    compileOnly("net.luckperms:api:5.4")
    compileOnly("me.clip:placeholderapi:2.11.6")
    compileOnly("com.nexomc:nexo:1.23")

    implementation("org.apache.logging.log4j:log4j-core:2.25.4")
    implementation("org.slf4j:slf4j-jdk14:2.0.17")
    implementation("io.javalin:javalin:6.7.0")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.java-websocket:Java-WebSocket:1.5.7")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("io.papermc.paper:paper-api:1.21-R0.1-SNAPSHOT")
}

val targetJavaVersion = 21
val generatedContractsDir = layout.buildDirectory.dir("generated/sources/resyncContracts/java")
val protocolContractFile = layout.projectDirectory.file("../shared/generated-contracts/resync-protocol.json")

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(targetJavaVersion))
}

sourceSets {
    main {
        java.srcDir(generatedContractsDir)
    }
}

val generateReSyncProtocolContract by tasks.registering {
    inputs.file(protocolContractFile)
    outputs.dir(generatedContractsDir)
    doLast {
        val root = JsonSlurper().parse(protocolContractFile.asFile) as Map<*, *>
        val packageNames = root["packageNames"] as Map<*, *>
        val constants = root["constants"] as Map<*, *>
        val byteConstants = (root["byteConstants"] as List<*>).map { it.toString() }.toSet()
        val shortConstants = (root["shortConstants"] as List<*>).map { it.toString() }.toSet()
        val packageName = packageNames["resync"].toString()
        val packageDir = generatedContractsDir.get().asFile.resolve(packageName.replace('.', '/'))
        packageDir.mkdirs()
        val output = packageDir.resolve("ReSyncProtocolContract.java")
        output.writeText(buildString {
            appendLine("package $packageName;")
            appendLine()
            appendLine("public final class ReSyncProtocolContract {")
            constants.forEach { (rawName, rawValue) ->
                val name = rawName.toString()
                val value = rawValue ?: return@forEach
                val line = when {
                    value is String -> "    public static final String $name = \"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\";"
                    byteConstants.contains(name) -> "    public static final byte $name = (byte) 0x${(value as Number).toInt().toString(16).uppercase().padStart(2, '0')};"
                    shortConstants.contains(name) -> "    public static final short $name = ${(value as Number).toInt()};"
                    else -> "    public static final int $name = ${(value as Number).toInt()};"
                }
                appendLine(line)
            }
            appendLine()
            appendLine("    private ReSyncProtocolContract() {")
            appendLine("    }")
            appendLine("}")
        })
    }
}

tasks {
    compileJava {
        dependsOn(generateReSyncProtocolContract)
    }

    build {
        dependsOn(shadowJar)
    }

    shadowJar {
        archiveClassifier.set("")
        mergeServiceFiles()
        relocate("io.javalin", "restudio.resync.libs.javalin")
        relocate("org.eclipse.jetty", "restudio.resync.libs.jetty")
        relocate("org.slf4j", "restudio.resync.libs.slf4j")
        relocate("com.google.gson", "restudio.resync.libs.gson")
        relocate("kotlin", "restudio.resync.libs.kotlin")
        relocate("org.java_websocket", "restudio.resync.libs.websocket")
    }

    processResources {
        val props = mapOf("version" to version)
        inputs.properties(props)
        filteringCharset = "UTF-8"
        filesMatching("plugin.yml") {
            expand(props)
        }
    }

    runServer {
        minecraftVersion("1.21")
    }

    test {
        useJUnitPlatform()
    }

    val validateNodeDefinitions by registering(JavaExec::class) {
        group = "verification"
        description = "Validate migrated node JSON definitions against source handler contracts"
        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set("restudio.resync.flow.validation.NodeDefinitionBuildValidator")
        args(projectDir.absolutePath)
        workingDir = projectDir
    }

    check {
        dependsOn(validateNodeDefinitions)
    }
}
