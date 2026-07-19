plugins {
    java
}

group = "restudio.request"
version = "1.0.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    compileOnly(fileTree(layout.projectDirectory.dir("../../build/libs").asFile) {
        include("ReSync-*.jar")
    })
    compileOnly("io.papermc.paper:paper-api:1.21-R0.1-SNAPSHOT")
    testImplementation(fileTree(layout.projectDirectory.dir("../../build/libs").asFile) {
        include("ReSync-*.jar")
    })
    testImplementation("io.papermc.paper:paper-api:1.21-R0.1-SNAPSHOT")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    archiveBaseName.set("request-extension")
}
