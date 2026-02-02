import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    java
    `maven-publish`
    id("com.gradleup.shadow") version "9.3.0"
    id("de.eldoria.plugin-yml.bukkit") version "0.8.0"
    id("net.kyori.indra.git") version "3.0.1"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://oss.sonatype.org/content/groups/public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks {
    withType<JavaCompile> {
        options.encoding = Charsets.UTF_8.name()
    }

    withType<ShadowJar> {
        fun reloc(vararg clazz: String) {
            clazz.forEach { relocate(it, "${project.group}.libs.$it") }
        }

        archiveFileName.set("SmartInvs-${project.version}.jar")
    }

    build {
        dependsOn(shadowJar)
    }
}

bukkit {
    name = "SmartInvs"
    description = "Inventory Framework"
    authors = listOf("MangoStudios")

    main = "${project.group}.SmartInvsPlugin"
    version = indraGit.commit()?.name?.take(7)
    apiVersion = "1.21"
}