package dev.lorenzzz.cundannatu.spigot.util;

import dev.lorenzzz.cundannatu.spigot.JailMiningPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.logging.Level;

/**
 * Aggancio opzionale a PlaceholderAPI.
 * <p>
 * I placeholder di questo plugin usano le graffe ({@code {time}}), quelli di
 * PlaceholderAPI le percentuali ({@code %player_name%}). Se PlaceholderAPI e'
 * installato i secondi vengono risolti prima del parsing del colore, altrimenti
 * restano nel testo cosi come sono scritti.
 */
public final class PlaceholderHook {

    private final JailMiningPlugin plugin;
    private final boolean available;

    public PlaceholderHook(JailMiningPlugin plugin) {
        this.plugin = plugin;
        this.available = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
        if (available) {
            plugin.getLogger().info("[Placeholder] PlaceholderAPI rilevato: i %placeholder% sono attivi");
        } else {
            plugin.getLogger().info("[Placeholder] PlaceholderAPI non presente: "
                    + "i testi con %placeholder% resteranno invariati");
        }
    }

    public boolean isAvailable() {
        return available;
    }

    /** Risolve i {@code %placeholder%} per il giocatore indicato. */
    public String apply(Player player, String text) {
        if (!available || player == null || text == null || text.indexOf('%') < 0) {
            return text;
        }
        try {
            return me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, text);
        } catch (Throwable throwable) {
            // Un placeholder difettoso di un altro plugin non deve rompere la scoreboard
            plugin.getLogger().log(Level.WARNING, "[Placeholder] Errore risolvendo un placeholder", throwable);
            return text;
        }
    }
}
