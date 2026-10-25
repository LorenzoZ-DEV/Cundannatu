package dev.lorenzzz.cundannatu.spigot.util;

import dev.lorenzzz.cundannatu.common.JailPlayerData;
import dev.lorenzzz.cundannatu.spigot.JailMiningPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;

/**
 * Costruisce la mappa dei placeholder usata da scoreboard, tab, chat e messaggi di join.
 * <p>
 * Placeholder sempre disponibili:
 * {@code {player} {displayname} {server} {world} {online} {max_players} {ping} {required}}.
 * <p>
 * Placeholder disponibili solo per i detenuti (per i liberi assumono i valori
 * neutri definiti in {@code jail-mining.messages.no-sentence-*}):
 * {@code {time} {credits} {reason} {progress} {bar} {missing}}.
 */
public final class Placeholders {

    private Placeholders() {
    }

    /** Placeholder di base, validi anche per chi non sta scontando una pena. */
    public static Map<String, String> of(JailMiningPlugin plugin, Player player) {
        Map<String, String> map = new HashMap<>();
        map.put("player", player.getName());
        map.put("displayname", player.getDisplayName());
        map.put("server", plugin.getJailServerName());
        map.put("world", player.getWorld().getName());
        map.put("online", String.valueOf(Bukkit.getOnlinePlayers().size()));
        map.put("max_players", String.valueOf(Bukkit.getMaxPlayers()));
        map.put("ping", String.valueOf(player.getPing()));
        map.put("required", String.valueOf(plugin.getCreditsForFreedom()));

        // Valori neutri: vengono sovrascritti da withJail() se il giocatore e' detenuto
        map.put("time", plugin.getRawMessage("no-sentence-time"));
        map.put("credits", "0");
        map.put("reason", plugin.getRawMessage("no-sentence-reason"));
        map.put("progress", "0.0");
        map.put("missing", "0");
        map.put("bar", plugin.buildProgressBar(0, plugin.getCreditsForFreedom()));

        // Contatori del lavoro in miniera
        map.put("blocks", "0");
        map.put("blocks_per_credit", String.valueOf(plugin.getBlocksPerCredit()));
        map.put("blocks_to_credit", String.valueOf(plugin.getBlocksPerCredit()));
        map.put("credit_progress", "0.0");
        map.put("credit_bar", plugin.buildProgressBar(0, 1));
        return map;
    }

    /** Aggiunge i placeholder relativi alla pena in corso. */
    public static Map<String, String> withJail(JailMiningPlugin plugin, Player player, JailPlayerData data) {
        Map<String, String> map = of(plugin, player);
        if (data == null) {
            return map;
        }
        int required = plugin.getCreditsForFreedom();
        long perCredit = plugin.getBlocksPerCredit();

        map.put("time", data.getFormattedRemaining());
        map.put("credits", String.valueOf(data.getJailCredits()));
        map.put("reason", data.getBanReason() == null ? "-" : data.getBanReason());
        map.put("progress", String.format("%.1f", data.getProgress() * 100D));
        map.put("missing", String.valueOf(Math.max(0, required - data.getJailCredits())));
        map.put("bar", plugin.buildProgressBar(data.getJailCredits(), required));

        // Avanzamento verso il prossimo credito: con soglie alte e' l unico
        // indicatore che si muove ad ogni blocco
        map.put("blocks", String.valueOf(data.getBlocksMined()));
        map.put("blocks_to_credit", String.valueOf(data.getBlocksToNextCredit(perCredit)));
        map.put("credit_progress", String.format("%.1f", data.getCreditProgress(perCredit) * 100D));
        map.put("credit_bar", plugin.buildProgressBar(data.getBlocksMined() % perCredit, perCredit));
        return map;
    }
}
