plugins { kotlin("jvm") version "2.3.20" }

group = "dev.feiyu"
version = "0.1.5"
repositories { mavenCentral() }
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

val ideHome = providers.gradleProperty("ideHome").get()
val sdk = files(fileTree("$ideHome/lib") { include("*.jar") },
    fileTree("$ideHome/plugins") { include("**/*.jar") })
layout.buildDirectory.set(layout.projectDirectory.dir(".tmp/build"))

dependencies {
    compileOnly(sdk)
    testImplementation(sdk)
    testImplementation("junit:junit:4.13.2")
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) } }
tasks.test { useJUnit() }
tasks.jar {
    from("LICENSE") { into("META-INF") }
}
tasks.register<Zip>("packagePlugin") {
    dependsOn(tasks.jar)
    archiveFileName.set("ide-mcp-tools-${project.version}.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    into("ide-mcp-tools/lib") { from(tasks.jar) }
}
