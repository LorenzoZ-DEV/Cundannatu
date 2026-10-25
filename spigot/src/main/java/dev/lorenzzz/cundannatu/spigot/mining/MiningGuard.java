package dev.lorenzzz.cundannatu.spigot.mining;

import dev.lorenzzz.cundannatu.common.JailPlayerData;
import dev.lorenzzz.cundannatu.spigot.JailMiningPlugin;
import dev.lorenzzz.cundannatu.spigot.util.Placeholders;
import dev.lorenzzz.cundannatu.spigot.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Controllo anti fast-break sulla miniera.
 * <p>
 * Non sostituisce un anticheat vero (LightningGrim, Grim, ecc.): quelli lavorano sui
 * pacchetti, qui guardiamo solo il ritmo di rottura dei blocchi, che e' il vettore
 * usato per macinare pena in fretta. Due segnali:
 * <ul>
 *     <li>due blocchi rotti a distanza inferiore a {@code min-break-interval-ms}</li>
 *     <li>troppi blocchi dentro la finestra {@code window-seconds}</li>
 * </ul>
 * Le prime violazioni vengono tollerate ({@code violations-before-penalty}) perche'
 * lag e ping alto producono falsi positivi; superata la soglia la pena aumenta e il
 * giocatore riceve un avviso esplicito.
 */
public class MiningGuard {

    private static final String PATH = "jail-mining.anti-cheat.";

    private final JailMiningPlugin plugin;
    private final Map<UUID, Tracker> trackers = new ConcurrentHashMap<>();

    public MiningGuard(JailMiningPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isEnabled() {
        return plugin.getConfig().getBoolean(PATH + "enabled", true);
    }

    /**
     * Registra un blocco rotto e valuta il ritmo di scavo.
     * <p>
     * Un blocco sospetto non paga mai (il chiamante annulla l evento), mentre la
     * penalita' sulla pena scatta solo dopo le violazioni tollerate e non piu' di una
     * ogni {@code penalty-cooldown-seconds}: senza quel freno un client con nuke
     * accumulerebbe decine di minuti e altrettanti messaggi in pochi secondi.
     *
     * @return true se il blocco va rifiutato
     */
    public boolean checkBreak(Player player, JailPlayerData data) {
        if (!isEnabled() || player.hasPermission("jailmining.bypass")) {
            return false;
        }

        long now = System.currentTimeMillis();
        long minInterval = plugin.getConfig().getLong(PATH + "min-break-interval-ms", 120L);
        int windowSeconds = Math.max(1, plugin.getConfig().getInt(PATH + "window-seconds", 5));
        int maxInWindow = plugin.getConfig().getInt(PATH + "max-blocks-per-window", 40);

        Tracker tracker = trackers.computeIfAbsent(player.getUniqueId(), uuid -> new Tracker());
        if (!tracker.record(now, minInterval, windowSeconds * 1000L, maxInWindow)) {
            return false;
        }

        int tolerated = plugin.getConfig().getInt(PATH + "violations-before-penalty", 3);
        long cooldown = plugin.getConfig().getLong(PATH + "penalty-cooldown-seconds", 5L) * 1000L;
        if (tracker.violations >= tolerated && now - tracker.lastPenalty >= cooldown) {
            tracker.violations = 0;
            tracker.lastPenalty = now;
            applyPenalty(player, data);
        }
        return true; // in ogni caso il blocco sospetto non viene accreditato
    }

    /** Aumenta la pena, avvisa il giocatore e segnala allo staff. */
    private void applyPenalty(Player player, JailPlayerData data) {
        long penaltySeconds = Math.max(0L, plugin.getConfig().getLong(PATH + "penalty-seconds", 60L));
        if (data != null && penaltySeconds > 0L) {
            // La penalita' e' l opposto dello scavo: il tempo residuo torna a salire
            data.setCurrentRemaining(data.getCurrentRemaining() + penaltySeconds * 1000L);
            data.setDirty(true);
            plugin.getDatabaseManager().updateProgress(data.getUuid(), data.getCurrentRemaining(),
                    data.getJailCredits(), data.getBlocksMined());
        }

        Map<String, String> placeholders = new HashMap<>(Placeholders.withJail(plugin, player, data));
        placeholders.put("penalty", String.valueOf(penaltySeconds));

        for (String line : plugin.getConfig().getStringList(PATH + "alert")) {
            plugin.sendRaw(player, line, placeholders);
        }
        player.playSound(player.getLocation(),
                plugin.getSound("on-cheat", Sound.ENTITY_VILLAGER_NO), 1.0F, 0.8F);

        plugin.getScoreboardUpdater().updateNow(player, data);
        notifyStaff(player, placeholders);

        plugin.getLogger().warning("[AntiCheat] Fast-break rilevato per " + player.getName()
                + ": pena aumentata di " + penaltySeconds + "s");
    }

    /** Avviso agli staffer con il permesso {@code jailmining.alerts}. */
    private void notifyStaff(Player player, Map<String, String> placeholders) {
        String raw = plugin.getConfig().getString(PATH + "staff-alert", "");
        if (raw == null || raw.isBlank()) {
            return;
        }
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission("jailmining.alerts") && !staff.equals(player)) {
                plugin.sendRaw(staff, raw, placeholders);
            }
        }
        plugin.getLogger().info("[AntiCheat] " + org.bukkit.ChatColor.stripColor(
                Text.format(plugin.applyGlobalPlaceholders(raw), placeholders)));
    }

    /** Dimentica il giocatore uscito, per non tenere stato inutile. */
    public void forget(UUID uuid) {
        trackers.remove(uuid);
    }

    public void clear() {
        trackers.clear();
    }

    /** Stato per giocatore: ultimo blocco, finestra scorrevole e violazioni accumulate. */
    private static final class Tracker {

        private long lastBreak;
        private long windowStart;
        private int blocksInWindow;
        private int violations;
        private long lastPenalty;

        /** @return true se questo blocco e' sospetto */
        private boolean record(long now, long minInterval, long windowMillis, int maxInWindow) {
            boolean suspicious = false;

            if (lastBreak > 0L && now - lastBreak < minInterval) {
                suspicious = true;
            }
            lastBreak = now;

            if (windowStart == 0L || now - windowStart > windowMillis) {
                windowStart = now;
                blocksInWindow = 1;
            } else {
                blocksInWindow++;
                if (blocksInWindow > maxInWindow) {
                    suspicious = true;
                    // La finestra riparte: senza reset ogni blocco successivo sarebbe violazione
                    windowStart = now;
                    blocksInWindow = 1;
                }
            }

            if (suspicious) {
                violations++;
            }
            return suspicious;
        }
    }
}
