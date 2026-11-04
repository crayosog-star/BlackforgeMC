plugins { java }

group = "net.blackforge"
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

tasks.jar { archiveFileName.set("BlackForgeCore.jar") }

// Renders isometric previews of the generated builds without a running server.
tasks.register<JavaExec>("preview") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("net.blackforge.core.build.PreviewMain")
    args(layout.projectDirectory.dir("previews").asFile.absolutePath)
}

// Renders every built-in arcade map: gradle arcadePreview  (or -Pgame=bedwars)
tasks.register<JavaExec>("arcadePreview") {
    classpath = sourceSets["main"].runtimeClasspath + sourceSets["main"].compileClasspath
    mainClass.set("net.blackforge.core.build.ArcadePreview")
    args(layout.projectDirectory.dir("previews/arcade").asFile.absolutePath, (project.findProperty("game") ?: "").toString().ifEmpty { "" })
}

// Walks every spawn build looking for traps and reachable lava: gradle walkcheck
tasks.register<JavaExec>("walkcheck") {
    classpath = sourceSets["main"].runtimeClasspath + sourceSets["main"].compileClasspath
    mainClass.set("net.blackforge.core.build.WalkCheck")
}

// Screenshots of the live worlds (real textures from the client jar): gradle shots -Pjar=<26.3.jar> [-Pshot=name -Pw=1920 -Ph=1080 -Pss=2]
tasks.register<JavaExec>("shots") {
    classpath = sourceSets["main"].runtimeClasspath + sourceSets["main"].compileClasspath
    mainClass.set("net.blackforge.core.build.Shots")
    maxHeapSize = "6g"
    args((project.findProperty("jar") ?: "").toString(), (project.findProperty("shot") ?: "").toString(), (project.findProperty("w") ?: "1920").toString(),
        (project.findProperty("h") ?: "1080").toString(), (project.findProperty("ss") ?: "2").toString(), layout.projectDirectory.dir("previews/live").asFile.absolutePath)
}

// Trailer frames (then tools/make_trailer.py): gradle trailer -Pjar=<26.3.jar> [-Pfrom=0 -Pto=99999]
tasks.register<JavaExec>("trailer") {
    classpath = sourceSets["main"].runtimeClasspath + sourceSets["main"].compileClasspath
    mainClass.set("net.blackforge.core.build.Trailer")
    maxHeapSize = "6g"
    args((project.findProperty("jar") ?: "").toString(), (project.findProperty("from") ?: "0").toString(), (project.findProperty("to") ?: "999999").toString(),
        layout.projectDirectory.dir("previews/trailer").asFile.absolutePath)
}

// Premiere trailer frames (then tools/make_premiere.py): gradle premiere -Pjar=<26.3.jar> -Pproduct=main|hero|short [-Pfrom=0 -Pto=999999 -Pevery=1 -Pss=2 -Pdir=<out> -Pw=1920 -Ph=1080]
tasks.register<JavaExec>("premiere") {
    classpath = sourceSets["main"].runtimeClasspath + sourceSets["main"].compileClasspath
    mainClass.set("net.blackforge.core.build.Premiere")
    maxHeapSize = "8g"
    val product = (project.findProperty("product") ?: "main").toString()
    val argv = mutableListOf((project.findProperty("jar") ?: "").toString(), product, (project.findProperty("from") ?: "0").toString(), (project.findProperty("to") ?: "999999").toString(),
        (project.findProperty("every") ?: "1").toString(), (project.findProperty("ss") ?: "2").toString(),
        (project.findProperty("dir") ?: layout.projectDirectory.dir("previews/premiere/" + product).asFile.absolutePath).toString())
    if (project.hasProperty("w")) { argv.add(project.findProperty("w").toString()); argv.add(project.findProperty("h").toString()) }
    args(argv)
}

// The five world bosses lined up in the KOTH arena (a stand-in picture): gradle bosses -Pjar=<26.3.jar> [-Pdir=out]
tasks.register<JavaExec>("bosses") {
    classpath = sourceSets["main"].runtimeClasspath + sourceSets["main"].compileClasspath
    mainClass.set("net.blackforge.core.build.BossShot")
    maxHeapSize = "6g"
    args((project.findProperty("jar") ?: "").toString(), (project.findProperty("dir") ?: layout.projectDirectory.dir("previews/bosses").asFile.absolutePath).toString())
}

// scratch render, remove before shipping: gradle shot -Pjar=<26.3.jar> [-Pw=640 -Ph=360 -Pss=1]
tasks.register<JavaExec>("shot") {
    classpath = sourceSets["main"].runtimeClasspath + sourceSets["main"].compileClasspath
    mainClass.set("net.blackforge.core.build.ScratchShot")
    maxHeapSize = "6g"
    args((project.findProperty("jar") ?: "").toString(),
         (project.findProperty("dir") ?: layout.projectDirectory.dir("previews/scratch").asFile.absolutePath).toString(),
         (project.findProperty("w") ?: "1920").toString(), (project.findProperty("h") ?: "1080").toString(), (project.findProperty("ss") ?: "2").toString())
}

// Renders the Infernal Kingdom (survival spawn rebuild, staging): gradle kingdom
tasks.register<JavaExec>("kingdom") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("net.blackforge.core.build.KingdomPreview")
    jvmArgs("-Xmx6g")
    args(layout.projectDirectory.dir("previews").asFile.absolutePath)
}
