// Modulo spigot: plugin del server jail (database, mining, scoreboard, comandi).

plugins {
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":common"))

    compileOnly("org.spigotmc:spigot-api:${rootProject.extra["spigotVersion"]}")
    compileOnly("com.gitlab.ruany:LiteBansAPI:${rootProject.extra["liteBansVersion"]}")
    compileOnly("com.google.code.gson:gson:${rootProject.extra["gsonVersion"]}")
    // spigot-api porta una versione di annotations senza TYPE_USE: serve quella recente
    compileOnly("org.jetbrains:annotations:26.0.2")

    // Database
    implementation("com.zaxxer:HikariCP:5.1.0")
    runtimeOnly("com.mysql:mysql-connector-j:8.4.0")

    // Scoreboard
    implementation("fr.mrmicky:fastboard:2.1.5")

    // MiniMessage (Adventure): formattazione dei testi di config.
    // Serve 4.22+ per il tag <shadow>, disponibile dal client 1.21.4.
    implementation("net.kyori:adventure-platform-bukkit:4.4.0")
    implementation("net.kyori:adventure-api:4.23.0")
    implementation("net.kyori:adventure-text-minimessage:4.23.0")
    implementation("net.kyori:adventure-text-serializer-legacy:4.23.0")
    implementation("net.kyori:adventure-text-serializer-gson:4.23.0")

    // PlaceholderAPI: opzionale, per i placeholder %...% dentro ai testi
    compileOnly("me.clip:placeholderapi:2.11.6")

    // Aikar: ACF + TaskChain
    implementation("co.aikar:acf-paper:0.5.1-SNAPSHOT")
    implementation("co.aikar:taskchain-bukkit:3.7.2")
}

tasks {
    // Solo il jar shaded viene distribuito: il jar "sottile" non serve
    jar {
        enabled = false
    }

    processResources {
        val props = mapOf("version" to project.version, "description" to project.description)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }

    shadowJar {
        archiveBaseName.set("Cundannatu")
        archiveClassifier.set("")

        // Adventure carica i propri provider via ServiceLoader: senza questo merge i
        // file META-INF/services restano con i nomi originali e puntano a classi che,
        // dopo la relocation, non esistono piu'.
        mergeServiceFiles()
        relocate("co.aikar.commands", "dev.lorenzzz.cundannatu.lib.acf")
        relocate("co.aikar.locales", "dev.lorenzzz.cundannatu.lib.locales")
        relocate("co.aikar.taskchain", "dev.lorenzzz.cundannatu.lib.taskchain")
        relocate("com.zaxxer.hikari", "dev.lorenzzz.cundannatu.lib.hikari")
        relocate("fr.mrmicky.fastboard", "dev.lorenzzz.cundannatu.lib.fastboard")
        // Adventure va rilocato per non litigare con le versioni di altri plugin
        relocate("net.kyori", "dev.lorenzzz.cundannatu.lib.kyori")
    }

    build {
        dependsOn(shadowJar)
    }
}
