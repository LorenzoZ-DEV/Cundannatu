package dev.lorenzzz.cundannatu.spigot.util;

import dev.lorenzzz.cundannatu.spigot.JailMiningPlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;

/**
 * Allinea il config.yml presente sul server con quello incluso nel jar.
 * <p>
 * Ad ogni avvio (e ad ogni {@code /jailadmin reload}) le chiavi nuove introdotte da
 * un aggiornamento del plugin vengono aggiunte automaticamente al file dell utente,
 * commenti compresi, senza toccare i valori gia' personalizzati e senza cancellare
 * eventuali chiavi aggiuntive.
 */
public final class ConfigUpdater {

    private ConfigUpdater() {
    }

    /**
     * Copia nel file dell utente tutte le chiavi mancanti rispetto al default del jar.
     *
     * @return numero di chiavi aggiunte (0 se il file era gia' aggiornato)
     */
    public static int update(JailMiningPlugin plugin, String resourceName) {
        YamlConfiguration defaults = loadDefaults(plugin, resourceName);
        if (defaults == null) {
            return 0;
        }

        List<String> added = merge(plugin.getConfig(), defaults);
        if (added.isEmpty()) {
            return 0;
        }

        plugin.saveConfig();
        plugin.getLogger().info("[Config] " + added.size() + " nuove opzioni aggiunte a " + resourceName + ":");
        added.forEach(key -> plugin.getLogger().info("[Config]  + " + key));
        return added.size();
    }

    /**
     * Copia in {@code current} le chiavi presenti solo in {@code defaults}, commenti inclusi.
     * I valori gia' impostati dall utente non vengono mai toccati.
     *
     * @return elenco delle chiavi aggiunte, in ordine di comparsa nei default
     */
    public static List<String> merge(FileConfiguration current, YamlConfiguration defaults) {
        List<String> added = new ArrayList<>();
        Set<String> newSections = new LinkedHashSet<>();

        for (String key : defaults.getKeys(true)) {
            // Le sezioni vengono create implicitamente insieme alle loro chiavi figlie:
            // ci limitiamo ad annotare quelle nuove per copiarne i commenti alla fine
            if (defaults.get(key) instanceof ConfigurationSection) {
                if (!current.contains(key)) {
                    newSections.add(key);
                }
                continue;
            }
            if (current.contains(key)) {
                continue;
            }
            current.set(key, defaults.get(key));
            // Dal 1.18 Bukkit conserva i commenti: li portiamo dietro alla chiave nuova
            current.setComments(key, defaults.getComments(key));
            current.setInlineComments(key, defaults.getInlineComments(key));
            added.add(key);
        }

        // Ora le sezioni esistono e possono ricevere il loro commento di intestazione
        for (String section : newSections) {
            if (current.contains(section)) {
                current.setComments(section, defaults.getComments(section));
                current.setInlineComments(section, defaults.getInlineComments(section));
            }
        }
        return added;
    }

    /** Carica il config.yml contenuto nel jar. */
    private static YamlConfiguration loadDefaults(JailMiningPlugin plugin, String resourceName) {
        try (InputStream stream = plugin.getResource(resourceName)) {
            if (stream == null) {
                plugin.getLogger().warning("[Config] Risorsa " + resourceName + " non trovata nel jar");
                return null;
            }
            return YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (Exception exception) {
            plugin.getLogger().log(Level.WARNING,
                    "[Config] Impossibile leggere i default di " + resourceName, exception);
            return null;
        }
    }
}
