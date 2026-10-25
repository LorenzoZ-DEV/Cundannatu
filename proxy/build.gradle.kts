// Modulo proxy: plugin Velocity che intercetta i ban LiteBans
// e dirotta i giocatori sul server jail.

plugins {
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":common"))

    compileOnly("com.velocitypowered:velocity-api:${rootProject.extra["velocityVersion"]}")
    annotationProcessor("com.velocitypowered:velocity-api:${rootProject.extra["velocityVersion"]}")

    compileOnly("com.gitlab.ruany:LiteBansAPI:${rootProject.extra["liteBansVersion"]}")
    compileOnly("com.google.code.gson:gson:${rootProject.extra["gsonVersion"]}")
}

tasks {
    // Solo il jar shaded viene distribuito: il jar "sottile" non serve
    jar {
        enabled = false
    }

    shadowJar {
        archiveBaseName.set("Cundannatu-Proxy")
        archiveClassifier.set("")
    }

    build {
        dependsOn(shadowJar)
    }
}
