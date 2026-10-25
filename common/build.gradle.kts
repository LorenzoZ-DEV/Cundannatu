// Modulo common: codice condiviso fra proxy e backend
// (modello dati, payload del canale plugin, costanti).

dependencies {
    api(project(":api"))
    // Gson e' fornito a runtime sia da Velocity sia da Spigot
    compileOnly("com.google.code.gson:gson:${rootProject.extra["gsonVersion"]}")
}

tasks.jar {
    archiveBaseName.set("Cundannatu-Common")
}
