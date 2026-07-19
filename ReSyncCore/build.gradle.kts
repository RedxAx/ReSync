import org.gradle.api.publish.maven.MavenPublication

plugins {
    `java-library`
    `maven-publish`
}

group = "restudio.resync"
version = "1.3.0"

repositories {
    mavenCentral()
}

publishing {
    publications {
        create<MavenPublication>("restudio") {
            from(components["java"])
        }
    }
}

dependencies {
    implementation("org.xerial:sqlite-jdbc:3.53.2.0")
    testImplementation("org.junit.jupiter:junit-jupiter:6.0.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.0.3")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.test {
    useJUnitPlatform()
}
