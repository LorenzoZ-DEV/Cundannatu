package dev.lorenzzz.cundannatu.spigot.command;

import co.aikar.commands.BaseCommand;
import co.aikar.commands.annotation.CommandAlias;
import co.aikar.commands.annotation.Default;
import co.aikar.commands.annotation.Description;
import dev.lorenzzz.cundannatu.common.JailPlayerData;
import dev.lorenzzz.cundannatu.spigot.JailMiningPlugin;
import dev.lorenzzz.cundannatu.spigot.util.Placeholders;
import dev.lorenzzz.cundannatu.spigot.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * {@code /jailhelp}: spiega al giocatore cosa gli e' successo e come funziona la
 * modalita' prigione, con i collegamenti cliccabili allo store e agli appeal.
 * <p>
 * Testo e pulsanti sono interamente configurabili in {@code jail-mining.help}:
 * esiste una versione per i detenuti e una per chi non ha pene in corso.
 */
@CommandAlias("jailhelp|perche|banhelp|comefunziona")
@Description("Spiega perche' sei in prigione e come uscirne")
public class JailHelpCommands extends BaseCommand {

    private static final String PATH = "jail-mining.help.";

    private final JailMiningPlugin plugin;

    public JailHelpCommands(JailMiningPlugin plugin) {
        this.plugin = plugin;
    }

    @Default
    @Description("Spiega perche' sei in prigione e come uscirne")
    public void onHelp(Player player) {
        if (!plugin.getConfig().getBoolean(PATH + "enabled", true)) {
            plugin.send(player, "help-disabled");
            return;
        }

        // Il dato puo' non essere in cache: rileggiamo in asincrono e stampiamo in sincrono
        plugin.<JailPlayerData>newChain()
                .asyncFirst(() -> plugin.getDatabaseManager().getJailedPlayer(player.getUniqueId()).join())
                .syncLast(data -> print(player, data))
                .execute((finished, exception) -> {
                    if (exception != null) {
                        plugin.getLogger().log(Level.SEVERE, "[Cmd] Errore in /jailhelp", exception);
                    }
                });
    }

    /**
     * Stampa il testo di aiuto.
     *
     * @param data dati della pena, {@code null} se il giocatore non e' detenuto
     */
    private void print(Player player, JailPlayerData data) {
        boolean jailed = data != null;
        Map<String, String> placeholders = jailed
                ? Placeholders.withJail(plugin, player, data)
                : Placeholders.of(plugin, player);

        String section = jailed ? "jailed" : "not-jailed";
        for (String line : plugin.getConfig().getStringList(PATH + section)) {
            // Ogni riga puo' essere scritta in MiniMessage o con i codici &
            plugin.sendRaw(player, line, placeholders);
        }

        if (!jailed && plugin.getConfig().getBoolean(PATH + "buttons-jailed-only", true)) {
            return; // i pulsanti dello store restano riservati a chi e' bannato
        }
        sendButtons(player, placeholders);
    }

    /** Invia i pulsanti cliccabili definiti in {@code jail-mining.help.buttons}. */
    private void sendButtons(Player player, Map<String, String> placeholders) {
        List<Map<?, ?>> buttons = plugin.getConfig().getMapList(PATH + "buttons");
        if (buttons.isEmpty()) {
            return;
        }

        for (Map<?, ?> entry : buttons) {
            String label = string(entry, "text");
            String url = string(entry, "url");
            if (label.isBlank() || url.isBlank()) {
                plugin.getLogger().warning("[Help] Pulsante senza 'text' o 'url' in config, ignorato");
                continue;
            }

            Component button = Text.component(plugin.applyGlobalPlaceholders(label), placeholders)
                    .clickEvent(ClickEvent.openUrl(url));

            String hover = string(entry, "hover");
            if (!hover.isBlank()) {
                button = button.hoverEvent(HoverEvent.showText(
                        Text.component(plugin.applyGlobalPlaceholders(hover), placeholders)));
            }
            plugin.audiences().player(player).sendMessage(button);
        }
    }

    private String string(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value);
    }
}
