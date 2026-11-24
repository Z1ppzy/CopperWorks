plugins {
    java
    // ./gradlew runServer — поднимает тестовый Paper-сервер с плагином
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

group = "dev.copperworks"
version = "0.1.0"

val minecraftVersion = "26.3"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.259-stable")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release = 25
    }

    processResources {
        val props = mapOf("version" to project.version)
        inputs.properties(props)
        filteringCharset = "UTF-8"
        filesMatching("paper-plugin.yml") {
            expand(props)
        }
    }

    runServer {
        // При первом запуске сервер попросит принять EULA: поставь eula=true в run/eula.txt
        minecraftVersion(minecraftVersion)
    }
}
