plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}
kotlin { jvmToolchain(21) }
application { mainClass.set("com.parsehub.sidecar.MainKt") }
dependencies {
    implementation(project(":kernel"))
    implementation("io.ktor:ktor-server-netty:${libs.versions.ktor.get()}")
    implementation("io.ktor:ktor-server-content-negotiation:${libs.versions.ktor.get()}")
    implementation("io.ktor:ktor-serialization-kotlinx-json:${libs.versions.ktor.get()}")
    testImplementation(kotlin("test"))
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")
}
val mergedServices = layout.buildDirectory.dir("merged-services")
val mergeServices by tasks.registering {
    dependsOn(tasks.named("classes"))
    inputs.files(configurations.runtimeClasspath, sourceSets.main.get().output)
    outputs.dir(mergedServices)
    doLast {
        val output = mergedServices.get().asFile
        output.deleteRecursively()
        val services = sortedMapOf<String, MutableSet<String>>()
        val roots = sourceSets.main.get().output.files + configurations.runtimeClasspath.get().files
        roots.filter { it.exists() }.forEach { root ->
            val tree = if (root.isDirectory) fileTree(root) else zipTree(root)
            tree.matching { include("META-INF/services/*") }.visit {
                if (!isDirectory) {
                    val providers = services.getOrPut(relativePath.pathString) { linkedSetOf() }
                    file.readLines().map { it.substringBefore('#').trim() }
                        .filter { it.isNotEmpty() }.forEach { providers.add(it) }
                }
            }
        }
        services.forEach { (name, providers) ->
            output.resolve(name).apply { parentFile.mkdirs(); writeText(providers.joinToString("\n", postfix = "\n")) }
        }
    }
}
tasks.register<Jar>("fatJar") {
    archiveFileName.set("parsehub-sidecar.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes["Main-Class"] = application.mainClass.get() }
    dependsOn(mergeServices)
    from(mergedServices)
    from(sourceSets.main.get().output) { exclude("META-INF/services/**") }
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) } }) {
        exclude("META-INF/services/**")
    }
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
}
