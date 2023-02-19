import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    java
    `maven-publish`
    id("com.github.johnrengelman.shadow") version "7.1.2"
    id("net.minecrell.plugin-yml.bukkit") version "0.5.2"
    id("net.kyori.indra.git") version "3.0.1"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://oss.sonatype.org/content/groups/public/")
}

dependencies {
    testImplementation("junit:junit:4.12")
    testImplementation("org.mockito:mockito-core:2.19.0")
    testImplementation("io.papermc.paper:paper-api:1.19.2-R0.1-SNAPSHOT")

    compileOnly("io.papermc.paper:paper-api:1.19.2-R0.1-SNAPSHOT")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
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
        dependsOn(shadowJar, publish)
    }
}

bukkit {
    name = "SmartInvs"
    description = "Inventory Framework"
    authors = listOf("MangoStudios")

    main = "${project.group}.SmartInvsPlugin"
    version = indraGit.commit()?.name?.take(7)
    apiVersion = "1.19"
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])

            repositories {
                maven {
                    credentials {
                        username = System.getProperty("MANGO_STUDIOS_REPO_USERNAME")
                        password = System.getProperty("MANGO_STUDIOS_REPO_PASSWORD")
                    }

                    url = uri("https://repo.mangostudios.uk/repository/internal/")
                }
            }
        }
    }
}
