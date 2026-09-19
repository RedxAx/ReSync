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
    api("org.xerial:sqlite-jdbc:3.53.2.0")
    testImplementation("org.junit.jupiter:junit-jupiter:6.0.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.0.3")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.test {
    useJUnitPlatform()
}

val browserJar by tasks.registering(Jar::class) {
    archiveClassifier.set("browser")
    dependsOn(tasks.classes)
    from(sourceSets.main.map { it.output })
    exclude("**/network/*HubStore*")
    exclude("**/migration/**")
    exclude("**/restore/**")
    exclude("**/upgrade/**")
    exclude("**/flow/migration/**")
    exclude("**/contract/diagnostic/DurableDiagnosticReportStore*")
    exclude("**/contract/diagnostic/DiagnosticCodeCatalogFiles*")
    exclude("**/flow/cache/CatalogCachePublicationTransport*")
    exclude("**/flow/cache/CatalogPublicationReceiptStore*")
    exclude("**/flow/graph/CompiledExecutionRunner*")
    exclude("**/flow/graph/CompiledPlanLease*")
    exclude("**/flow/graph/CompiledPlanAuthority*")
    exclude("**/flow/graph/GraphCompiler*")
    exclude("**/flow/graph/GraphValidator*")
    exclude("**/flow/function/CompiledFunctionRunner*")
    exclude("**/flow/function/CompiledFunctionResolver*")
    exclude("**/flow/function/CompiledCallableAuthority*")
    exclude("**/flow/function/TypedFunction*")
    exclude("**/flow/function/FunctionSourceMaterializer*")
    exclude("**/flow/catalog/CatalogActivation*")
    exclude("**/flow/catalog/CatalogRuntime*")
    exclude("**/flow/catalog/CatalogBindingProof*")
    exclude("**/flow/catalog/CatalogCompiler*")
    exclude("**/flow/catalog/CatalogSourceIngestor*")
    exclude("**/flow/catalog/CatalogSourceIndex*")
    exclude("**/flow/catalog/CatalogStartupIndex*")
    exclude("**/flow/validation/**")
    exclude("**/flow/runtime/RuntimeBindingRegistry*")
    exclude("**/flow/runtime/RuntimeBinding.class")
    exclude("**/flow/runtime/RuntimeBinding$*.class")
    exclude("**/flow/runtime/RuntimeBindingCollision*")
    exclude("**/flow/runtime/RuntimePlanLease*")
    exclude("**/flow/runtime/RuntimeReceiptStore*")
    exclude("**/flow/runtime/RuntimeExecution*")
    exclude("**/flow/runtime/RuntimeInvocation*")
    exclude("**/flow/runtime/ReplacementRuntime*")
    exclude("**/flow/runtime/RuntimeCancellation*")
    exclude("**/flow/runtime/RuntimeOperationHandler*")
    exclude("**/flow/runtime/RuntimeOperationCancelled*")
    exclude("**/flow/runtime/CompiledRuntime*")
    exclude("**/flow/runtime/RuntimePrincipal*")
    exclude("**/flow/runtime/RuntimeSecurity*")
    exclude("**/flow/runtime/RuntimeAudit*")
    exclude("**/flow/runtime/RuntimeAuthority*")
    exclude("**/flow/runtime/RuntimeProviderLifecycle*")
    exclude("**/flow/runtime/RuntimeCapabilityUnavailable*")
    exclude("**/flow/runtime/RuntimeUnload*")
    exclude("**/flow/runtime/RuntimeRegistrySnapshot*")
    exclude("**/flow/runtime/RuntimeLeaseInput*")
}
