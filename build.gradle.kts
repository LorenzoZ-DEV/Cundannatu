plugins {
    // Applicato solo dai moduli che producono il jar per il server
    id("com.gradleup.shadow") version "9.2.2" apply false
}

// Versioni condivise, lette dai singoli moduli con rootProject.extra
extra["spigotVersion"] = "1.21.11-R0.1-SNAPSHOT"
extra["velocityVersion"] = "3.3.0-SNAPSHOT"
extra["liteBansVersion"] = "0.6.1"
extra["gsonVersion"] = "2.11.0"

subprojects {
    apply(plugin = "java-library")

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
        maven("https://repo.aikar.co/content/groups/aikar/")
        maven("https://oss.sonatype.org/content/groups/public/")
        maven("https://jitpack.io")
        maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    }

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion = JavaLanguageVersion.of(21)
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-parameters")
    }

    tasks.withType<Javadoc>().configureEach {
        options.encoding = "UTF-8"
    }
}
