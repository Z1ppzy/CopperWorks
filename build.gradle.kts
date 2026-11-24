plugins {
    java
    // ./gradlew runServer — поднимает тестовый Paper-сервер с плагином
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

group = "dev.copperworks"
version = "0.2.0"

val minecraftVersion = "26.3"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.259-stable")

    // Тесты работают без сервера: MessagesConsistencyTest читает исходники как текст,
    // TraitsTest строит Settings из config.yml и проверяет чистую логику опыта и черт.
    testImplementation("io.papermc.paper:paper-api:26.3.build.259-stable")
    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.yaml:snakeyaml:2.5")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release = 25
    }

    compileTestJava {
        options.encoding = "UTF-8"
        options.release = 25
    }

    test {
        useJUnitPlatform()
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
