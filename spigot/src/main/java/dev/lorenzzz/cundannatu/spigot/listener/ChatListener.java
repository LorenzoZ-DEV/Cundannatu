package dev.lorenzzz.cundannatu.spigot.listener;

import dev.lorenzzz.cundannatu.common.JailPlayerData;
import dev.lorenzzz.cundannatu.spigot.JailMiningPlugin;
import dev.lorenzzz.cundannatu.spigot.util.Placeholders;
import dev.lorenzzz.cundannatu.spigot.util.Text;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.Map;

/**
 * Applica il formato di chat configurabile in {@code jail-mining.chat}.
 * <p>
 * Sono previsti due formati: uno per i detenuti ({@code format-jailed}) e uno per i
 * giocatori liberi ({@code format-free}). L evento e' asincrono, quindi si legge
 * soltanto la cache in memoria: nessuna query al database dal thread della chat.
 */
public class ChatListener implements Listener {

    private static final String PATH = "jail-mining.chat.";

    /**
     * Marcatori temporanei costruiti con caratteri di controllo (impossibili da scrivere
     * in config): proteggono i token di String.format mentre si applicano colori e placeholder.
     */
    private static final String MESSAGE_TOKEN = (char) 1 + "MSG" + (char) 1;
    private static final String DISPLAY_TOKEN = (char) 2 + "DSP" + (char) 2;

    private final JailMiningPlugin plugin;

    public ChatListener(JailMiningPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        if (!plugin.getConfig().getBoolean(PATH + "enabled", true)) {
            return;
        }

        Player player = event.getPlayer();
        // Evento asincrono: usiamo solo la cache in memoria, mai il database
        JailPlayerData data = plugin.getDatabaseManager().getCached(player.getUniqueId());
        boolean jailed = data != null;

        if (jailed && plugin.getConfig().getBoolean(PATH + "mute-jailed", false)
                && !player.hasPermission("jailmining.bypass")) {
            event.setCancelled(true);
            plugin.sendRaw(player, plugin.getConfig().getString(PATH + "mute-message",
                    "&cNon puoi parlare mentre sconti la pena."), null);
            return;
        }

        String raw = plugin.getConfig().getString(PATH + (jailed ? "format-jailed" : "format-free"), "");
        if (raw == null || raw.isBlank()) {
            return; // formato non configurato: resta quello del server
        }

        Map<String, String> placeholders = jailed
                ? Placeholders.withJail(plugin, player, data)
                : Placeholders.of(plugin, player);

        // I colori dentro al messaggio sono opzionali e sotto permesso
        if (plugin.getConfig().getBoolean(PATH + "allow-color-codes", false)
                && player.hasPermission("jailmining.chat.color")) {
            event.setMessage(Text.color(event.getMessage()));
        }

        event.setFormat(buildFormat(raw, placeholders));
    }

    /**
     * Converte il formato di config nel pattern richiesto da {@code AsyncPlayerChatEvent}:
     * {@code %1$s} = nome visualizzato, {@code %2$s} = messaggio. Gli altri {@code %}
     * vengono raddoppiati per non far fallire {@link String#format}.
     */
    private String buildFormat(String raw, Map<String, String> placeholders) {
        String tokenized = raw
                .replace("{message}", MESSAGE_TOKEN)
                .replace("{displayname}", DISPLAY_TOKEN);

        return Text.format(tokenized, placeholders)
                .replace("%", "%%")
                .replace(MESSAGE_TOKEN, "%2$s")
                .replace(DISPLAY_TOKEN, "%1$s");
    }
}
