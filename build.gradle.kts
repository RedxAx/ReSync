import groovy.json.JsonSlurper

plugins {
    id("java")
    id("com.gradleup.shadow") version "8.3.0"
    id("xyz.jpenilla.run-paper") version "2.3.0"
}

group = "restudio.resync"
version = "1.3.0"

repositories {
    mavenCentral()
    maven("https://repo.codemc.io/repository/maven-releases/")
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://oss.sonatype.org/content/groups/public/")
    maven("https://jitpack.io")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    maven("https://repo.nexomc.com/releases")
}

dependencies {
    implementation(project(":ReSyncCore"))
    compileOnly("io.papermc.paper:paper-api:1.21.10-R0.1-SNAPSHOT")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1")
    compileOnly("net.luckperms:api:5.4")
    compileOnly("me.clip:placeholderapi:2.11.6")
    compileOnly("com.nexomc:nexo:1.23")
    compileOnly("net.dmulloy2:ProtocolLib:5.4.0")

    implementation("org.apache.logging.log4j:log4j-core:2.25.4")
    implementation("org.slf4j:slf4j-jdk14:2.0.17")
    implementation("io.javalin:javalin:6.7.0")
    compileOnly("com.google.code.gson:gson:2.10.1")
    implementation("org.java-websocket:Java-WebSocket:1.5.7")
    implementation("com.github.retrooper:packetevents-spigot:2.12.1")

    testImplementation("org.junit.jupiter:junit-jupiter:6.0.3")
    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v1.21:4.99.0")
    testImplementation("io.papermc.paper:paper-api:1.21.10-R0.1-SNAPSHOT")
    testImplementation("com.google.code.gson:gson:2.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.0.3")
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
        val resources = (root["resources"] as? List<*>) ?: emptyList<Any>()
        val byteConstants = (root["byteConstants"] as List<*>).map { it.toString() }.toSet()
        val shortConstants = (root["shortConstants"] as List<*>).map { it.toString() }.toSet()
        val packageName = packageNames["resync"].toString()
        val packageDir = generatedContractsDir.get().asFile.resolve(packageName.replace('.', '/'))
        packageDir.mkdirs()
        val output = packageDir.resolve("ReSyncProtocolContract.java")
        fun quoted(value: Any?) = "\"${value.toString().replace("\\", "\\\\").replace("\"", "\\\"")}\""
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
            appendLine("    public record ResourceFlowPackets(byte request, byte listRequest, byte data, byte list, byte save, byte delete, byte saveAck) {")
            appendLine("    }")
            appendLine()
            appendLine("    public record ResourceContract(String typeId, String displayName, String defaultFolder, boolean jsonStorageSupported, ResourceFlowPackets flowPackets) {")
            appendLine("    }")
            appendLine()
            appendLine("    public static final ResourceContract[] RESOURCE_CONTRACTS = new ResourceContract[] {")
            resources.forEachIndexed { index, rawResource ->
                val resource = rawResource as Map<*, *>
                val flowPackets = resource["flowPackets"] as? Map<*, *>
                val packetText = if (flowPackets == null) {
                    "null"
                } else {
                    "new ResourceFlowPackets((byte) 0x${(flowPackets["request"] as Number).toInt().toString(16).uppercase().padStart(2, '0')}, (byte) 0x${(flowPackets["listRequest"] as Number).toInt().toString(16).uppercase().padStart(2, '0')}, (byte) 0x${(flowPackets["data"] as Number).toInt().toString(16).uppercase().padStart(2, '0')}, (byte) 0x${(flowPackets["list"] as Number).toInt().toString(16).uppercase().padStart(2, '0')}, (byte) 0x${(flowPackets["save"] as Number).toInt().toString(16).uppercase().padStart(2, '0')}, (byte) 0x${(flowPackets["delete"] as Number).toInt().toString(16).uppercase().padStart(2, '0')}, (byte) 0x${(flowPackets["saveAck"] as Number).toInt().toString(16).uppercase().padStart(2, '0')})"
                }
                val suffix = if (index == resources.lastIndex) "" else ","
                appendLine("        new ResourceContract(${quoted(resource["typeId"])}, ${quoted(resource["displayName"])}, ${quoted(resource["defaultFolder"])}, ${resource["jsonStorageSupported"] == true}, $packetText)$suffix")
            }
            appendLine("    };")
            appendLine()
            appendLine("    public static ResourceContract resource(String typeId) {")
            appendLine("        for (ResourceContract resource : RESOURCE_CONTRACTS) {")
            appendLine("            if (resource.typeId().equals(typeId)) {")
            appendLine("                return resource;")
            appendLine("            }")
            appendLine("        }")
            appendLine("        return null;")
            appendLine("    }")
            appendLine()
            appendLine("    public static DialogResource dialogResource(com.google.gson.JsonObject json, String fallbackId) {")
            appendLine("        return new DialogResource(json, fallbackId);")
            appendLine("    }")
            appendLine()
            appendLine("    public static final class DialogResource {")
            appendLine("        private final com.google.gson.JsonObject json;")
            appendLine("        private final String fallbackId;")
            appendLine()
            appendLine("        private DialogResource(com.google.gson.JsonObject json, String fallbackId) {")
            appendLine("            this.json = json != null ? json : new com.google.gson.JsonObject();")
            appendLine("            this.fallbackId = fallbackId == null || fallbackId.isBlank() ? \"dialog\" : fallbackId;")
            appendLine("        }")
            appendLine()
            appendLine("        public com.google.gson.JsonObject json() {")
            appendLine("            return json;")
            appendLine("        }")
            appendLine()
            appendLine("        public void applyDefaults(String defaultFolder) {")
            appendLine("            if (!json.has(\"id\") || text(\"id\", \"\").isBlank()) json.addProperty(\"id\", fallbackId);")
            appendLine("            if (!json.has(\"displayName\")) json.addProperty(\"displayName\", text(\"id\", fallbackId));")
            appendLine("            if (!json.has(\"folder\")) json.addProperty(\"folder\", defaultFolder == null ? \"Content/Dialogs\" : defaultFolder);")
            appendLine("            if (!json.has(\"enabled\")) json.addProperty(\"enabled\", true);")
            appendLine("            if (!json.has(\"type\")) json.addProperty(\"type\", \"minecraft:multi_action\");")
            appendLine("            if (!json.has(\"title\")) json.addProperty(\"title\", displayName());")
            appendLine("            ensureArray(\"body\");")
            appendLine("            ensureArray(\"inputs\");")
            appendLine("            ensureArray(\"actions\");")
            appendLine("            if (!json.has(\"can_close_with_escape\")) json.addProperty(\"can_close_with_escape\", true);")
            appendLine("            if (!json.has(\"after_action\")) json.addProperty(\"after_action\", \"close\");")
            appendLine("            if (!json.has(\"columns\")) json.addProperty(\"columns\", 1);")
            appendLine("        }")
            appendLine()
            appendLine("        public String displayName() {")
            appendLine("            return text(\"displayName\", text(\"id\", fallbackId));")
            appendLine("        }")
            appendLine()
            appendLine("        public String title() {")
            appendLine("            return text(\"title\", displayName());")
            appendLine("        }")
            appendLine()
            appendLine("        public String externalTitle() {")
            appendLine("            return text(\"external_title\", displayName());")
            appendLine("        }")
            appendLine()
            appendLine("        public String type() {")
            appendLine("            return text(\"type\", \"minecraft:multi_action\");")
            appendLine("        }")
            appendLine()
            appendLine("        public boolean canCloseWithEscape() {")
            appendLine("            return bool(\"can_close_with_escape\", true);")
            appendLine("        }")
            appendLine()
            appendLine("        public boolean pause() {")
            appendLine("            return bool(\"pause\", true);")
            appendLine("        }")
            appendLine()
            appendLine("        public String afterAction() {")
            appendLine("            return text(\"after_action\", \"close\");")
            appendLine("        }")
            appendLine()
            appendLine("        public int columns() {")
            appendLine("            return integer(\"columns\", 1);")
            appendLine("        }")
            appendLine()
            appendLine("        public java.util.List<com.google.gson.JsonObject> body() {")
            appendLine("            return objectArray(\"body\");")
            appendLine("        }")
            appendLine()
            appendLine("        public java.util.List<com.google.gson.JsonObject> inputs() {")
            appendLine("            return objectArray(\"inputs\");")
            appendLine("        }")
            appendLine()
            appendLine("        public java.util.List<com.google.gson.JsonObject> actions() {")
            appendLine("            return objectArray(\"actions\");")
            appendLine("        }")
            appendLine()
            appendLine("        private void ensureArray(String key) {")
            appendLine("            if (!json.has(key) || !json.get(key).isJsonArray()) json.add(key, new com.google.gson.JsonArray());")
            appendLine("        }")
            appendLine()
            appendLine("        private java.util.List<com.google.gson.JsonObject> objectArray(String key) {")
            appendLine("            java.util.List<com.google.gson.JsonObject> values = new java.util.ArrayList<>();")
            appendLine("            com.google.gson.JsonArray array = json.has(key) && json.get(key).isJsonArray() ? json.getAsJsonArray(key) : new com.google.gson.JsonArray();")
            appendLine("            for (com.google.gson.JsonElement element : array) if (element != null && element.isJsonObject()) values.add(element.getAsJsonObject());")
            appendLine("            return values;")
            appendLine("        }")
            appendLine()
            appendLine("        private String text(String key, String fallback) {")
            appendLine("            return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : fallback;")
            appendLine("        }")
            appendLine()
            appendLine("        private boolean bool(String key, boolean fallback) {")
            appendLine("            return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsBoolean() : fallback;")
            appendLine("        }")
            appendLine()
            appendLine("        private int integer(String key, int fallback) {")
            appendLine("            return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsInt() : fallback;")
            appendLine("        }")
            appendLine("    }")
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
        relocate("kotlin", "restudio.resync.libs.kotlin")
        relocate("org.java_websocket", "restudio.resync.libs.websocket")
        relocate("com.github.retrooper.packetevents", "restudio.resync.libs.packetevents.api")
        relocate("io.github.retrooper.packetevents", "restudio.resync.libs.packetevents.impl")
        dependencies {
            exclude(dependency("com.google.code.gson:gson:.*"))
        }
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
        minecraftVersion("1.21.10")
    }

    test {
        useJUnitPlatform()
    }

    val validateNodeDefinitions by registering(JavaExec::class) {
        group = "verification"
        description = "Validate migrated node JSON definitions against source handler contracts"
        classpath = sourceSets["main"].runtimeClasspath + sourceSets["main"].compileClasspath
        mainClass.set("restudio.resync.flow.validation.NodeDefinitionBuildValidator")
        args(projectDir.absolutePath)
        workingDir = projectDir
    }

    check {
        dependsOn(validateNodeDefinitions)
    }
}
