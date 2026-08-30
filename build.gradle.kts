import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import java.nio.charset.StandardCharsets
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test

plugins {
    java
    alias(libs.plugins.run.paper)
    alias(libs.plugins.shadow)
    alias(libs.plugins.spotless)
    checkstyle
    pmd
    alias(libs.plugins.spotbugs)
}

group = "org.aincraft"
version = providers.gradleProperty("version").getOrElse("2026.08.29-SNAPSHOT")

description = "MobMatrix Paper plugin"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    compileOnly(libs.paper.api)
    compileOnly(libs.mapgui.api)
    testCompileOnly(libs.paper.api)
    testCompileOnly(libs.mapgui.api)
    testRuntimeOnly(libs.paper.api)
    testRuntimeOnly(libs.mapgui.api)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    implementation(libs.sqlite.jdbc)

    checkstyle(libs.checkstyle)
    spotbugs(libs.spotbugs.engine)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = StandardCharsets.UTF_8.name()
    options.release = 25
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("file.encoding", StandardCharsets.UTF_8.name())
}

tasks.processResources {
    filesMatching("paper-plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.runServer {
    minecraftVersion("26.2")
}

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    configurations = listOf(project.configurations.runtimeClasspath.get())
    dependencies {
        exclude(dependency("io.github.flog99:mapgui-api:.*"))
    }
}

tasks.named("assemble") {
    dependsOn(tasks.named("shadowJar"))
}

spotless {
    java {
        target("src/**/*.java")
        googleJavaFormat("1.36.1")
    }
}

checkstyle {
    toolVersion = "13.11.0"
    configFile = file("config/checkstyle/checkstyle.xml")
}

pmd {
    toolVersion = "7.26.0"
    isConsoleOutput = true
    isIgnoreFailures = false
    ruleSetFiles = files("config/pmd/pmd.xml")
    ruleSets = emptyList()
}

spotbugs {
    toolVersion.set("4.9.7")
    ignoreFailures.set(false)
    excludeFilter.set(file("config/spotbugs/exclude.xml"))
}

tasks.named("check") {
    dependsOn(
        tasks.named("spotlessCheck"),
        tasks.named("checkstyleMain"),
        tasks.named("checkstyleTest"),
        tasks.named("spotbugsTest"),
        tasks.named("pmdMain"),
        tasks.named("pmdTest"),
        tasks.named("spotbugsMain"),
        tasks.named("test"),
    )
}
