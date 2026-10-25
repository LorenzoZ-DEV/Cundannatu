// Modulo API: contratto pubblico per gli altri plugin.
// Nessuna dipendenza dalla piattaforma, solo Java puro.

dependencies {
    compileOnly("org.jetbrains:annotations:26.0.2")
}

tasks.jar {
    archiveBaseName.set("Cundannatu-API")
}
