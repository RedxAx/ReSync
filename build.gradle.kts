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
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21-R0.1-SNAPSHOT")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1")
    compileOnly("net.luckperms:api:5.4")
    compileOnly("me.clip:placeholderapi:2.11.6")

    implementation("org.apache.logging.log4j:log4j-core:2.25.2")
    implementation("org.slf4j:slf4j-jdk14:2.0.17")
    implementation("io.javalin:javalin:6.7.0")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.java-websocket:Java-WebSocket:1.5.7")
}

val targetJavaVersion = 21

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(targetJavaVersion))
}

tasks {
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
