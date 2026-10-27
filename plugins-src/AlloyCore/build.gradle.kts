plugins { java }

group = "net.alloymc"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://jitpack.io")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.222-stable")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1") { isTransitive = false }
    testImplementation("io.papermc.paper:paper-api:26.3.build.222-stable")
    testImplementation("com.github.MilkBowl:VaultAPI:1.7.1") { isTransitive = false }
    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped"); showStandardStreams = false; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }

tasks.withType<JavaCompile> { options.encoding = "UTF-8" }

tasks.jar { archiveFileName.set("AlloyCore.jar") }

// Renders isometric previews of the generated builds without a running server.
tasks.register<JavaExec>("preview") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("net.alloymc.core.build.PreviewMain")
    args(layout.projectDirectory.dir("previews").asFile.absolutePath)
}

// Renders every built-in arcade map: gradle arcadePreview  (or -Pgame=bedwars)
tasks.register<JavaExec>("arcadePreview") {
    classpath = sourceSets["main"].runtimeClasspath + sourceSets["main"].compileClasspath
    mainClass.set("net.alloymc.core.build.ArcadePreview")
    args(layout.projectDirectory.dir("previews/arcade").asFile.absolutePath, (project.findProperty("game") ?: "").toString().ifEmpty { "" })
}

// Walks every spawn build looking for traps and reachable lava: gradle walkcheck
tasks.register<JavaExec>("walkcheck") {
    classpath = sourceSets["main"].runtimeClasspath + sourceSets["main"].compileClasspath
    mainClass.set("net.alloymc.core.build.WalkCheck")
}
