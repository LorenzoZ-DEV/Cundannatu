package dev.lorenzzz.cundannatu.spigot.listener;

import dev.lorenzzz.cundannatu.spigot.JailMiningPlugin;
import dev.lorenzzz.cundannatu.common.JailPlayerData;
import dev.lorenzzz.cundannatu.spigot.api.event.PlayerReleasedEvent;
import dev.lorenzzz.cundannatu.spigot.util.Placeholders;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Cuore del sistema: ogni blocco di carbone rotto riduce il ban e assegna crediti.
 * <p>
 * L aggiornamento in memoria e' immediato (cache del DatabaseManager), mentre la
 * scrittura su MySQL avviene in una catena asincrona di Aikar TaskChain.
 */
public class BlockBreakListener implements Listener {

    /** Intervallo minimo fra due avvisi di divieto allo stesso giocatore. */
    private static final long WARN_COOLDOWN_MILLIS = 2_000L;

    private final JailMiningPlugin plugin;

    /** Ultimo avviso inviato: scavare la pietra rigenerata non deve intasare la chat. */
    private final Map<UUID, Long> lastWarning = new ConcurrentHashMap<>();

    public BlockBreakListener(JailMiningPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        JailPlayerData data = plugin.getDatabaseManager().getCached(uuid);
        if (data == null) {
            return; // non e' un detenuto: nessuna logica jail
        }
        if (!plugin.getCoalBlocks().contains(event.getBlock().getType())) {
            // In prigione si scava solo carbone
            if (plugin.isOnlyCoalMining() && !player.hasPermission("jailmining.bypass")) {
                event.setCancelled(true);
                warn(player, "only-coal");
            }
            return;
        }
        if (plugin.getReleaseService().isReleasing(uuid)) {
            return; // liberazione gia' in corso
        }

        // Controllo anti fast-break: se scatta, il blocco non paga e la pena sale
        if (plugin.getMiningGuard().checkBreak(player, data)) {
            event.setCancelled(true);
            return;
        }

        // Aggiornamento immediato in memoria: la scoreboard legge da qui.
        // Il tempo cala ad ogni blocco, i crediti solo al raggiungimento della soglia
        // (jail-mining.blocks-per-credit): scavarne uno non basta quasi mai.
        long remaining = data.reduceRemaining(plugin.getCarboneReductionMillis());
        int creditsGained = data.mineBlock(plugin.getBlocksPerCredit(), plugin.getCreditsPerCoal());
        int credits = data.getJailCredits();
        long blocksMined = data.getBlocksMined();
        data.setDirty(true);

        if (creditsGained > 0) {
            notifyCreditEarned(player, data, creditsGained);
        }

        Location location = event.getBlock().getLocation().add(0.5D, 0.5D, 0.5D);
        playFeedback(player, location);

        // Il carbone viene sostituito e riappare dopo il ritardo configurato
        if (!plugin.getOreRegenManager().shouldDropItems()) {
            event.setDropItems(false);
            event.setExpToDrop(0);
        }
        plugin.getOreRegenManager().scheduleRegen(event.getBlock());

        // UPDATE asincrono sul database (pattern async di Aikar)
        plugin.<Void>newChain()
                .async(() -> plugin.getDatabaseManager()
                        .updateProgress(uuid, remaining, credits, blocksMined).join())
                .sync(() -> {
                    data.setDirty(false);
                    plugin.getScoreboardUpdater().updateNow(player, data);
                })
                .execute((finished, exception) -> {
                    if (exception != null) {
                        plugin.getLogger().log(Level.SEVERE,
                                "[Mining] Errore nell update asincrono di " + uuid, exception);
                    }
                });

        plugin.getLogger().fine("[Mining] " + player.getName() + " blocchi -> " + blocksMined
                + ", crediti -> " + credits
                + " (residuo " + JailPlayerData.formatDuration(remaining) + ")");

        // Condizioni di uscita dalla prigione
        if (credits >= plugin.getCreditsForFreedom()) {
            plugin.getReleaseService().release(player, data, PlayerReleasedEvent.Reason.CREDITS);
        } else if (remaining <= 0L) {
            plugin.getReleaseService().release(player, data, PlayerReleasedEvent.Reason.TIME_SERVED);
        }
    }

    /** In prigione non si costruisce. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (!plugin.isJailed(player.getUniqueId()) || player.hasPermission("jailmining.bypass")) {
            return;
        }
        if (!plugin.isOnlyCoalMining()) {
            return;
        }
        event.setCancelled(true);
        warn(player, "no-place");
    }

    /** Avviso di divieto con throttle: al massimo uno ogni due secondi. */
    private void warn(Player player, String messageKey) {
        long now = System.currentTimeMillis();
        Long previous = lastWarning.get(player.getUniqueId());
        if (previous != null && now - previous < WARN_COOLDOWN_MILLIS) {
            return;
        }
        lastWarning.put(player.getUniqueId(), now);
        plugin.send(player, messageKey);
    }

    /**
     * Avvisa il detenuto quando un credito matura davvero.
     * <p>
     * Con soglie alte e' l unico momento in cui vede muoversi il contatore, quindi
     * merita messaggio e suono dedicati.
     */
    private void notifyCreditEarned(Player player, JailPlayerData data, int amount) {
        Map<String, String> placeholders = new HashMap<>(Placeholders.withJail(plugin, player, data));
        placeholders.put("amount", String.valueOf(amount));
        plugin.send(player, "credit-earned", placeholders);
        player.playSound(player.getLocation(),
                plugin.getSound("on-credit", Sound.ENTITY_EXPERIENCE_ORB_PICKUP), 1.0F, 1.0F);
    }

    /** Effetti visivi e sonori sul blocco rotto. */
    private void playFeedback(Player player, Location location) {
        Sound sound = plugin.getSound("on-break", Sound.BLOCK_STONE_BREAK);
        player.playSound(location, sound, 1.0F, 1.2F);

        Particle particle = resolveParticle();
        if (particle == null || location.getWorld() == null) {
            return;
        }
        try {
            if (particle.getDataType() == Particle.DustOptions.class) {
                Particle.DustOptions options = new Particle.DustOptions(resolveColor(), 1.5F);
                location.getWorld().spawnParticle(particle, location, 15, 0.3D, 0.3D, 0.3D, 0.0D, options);
            } else {
                location.getWorld().spawnParticle(particle, location, 15, 0.3D, 0.3D, 0.3D, 0.0D);
            }
        } catch (Exception exception) {
            plugin.getLogger().log(Level.WARNING, "[Mining] Particella non valida in config", exception);
        }
    }

    /** REDSTONE e' stata rinominata DUST nelle versioni recenti: proviamo entrambe. */
    private Particle resolveParticle() {
        String name = plugin.getConfig().getString("jail-mining.particles.on-break", "REDSTONE");
        if (name == null || name.isBlank()) {
            return null;
        }
        String upper = name.toUpperCase(Locale.ROOT);
        try {
            return Particle.valueOf(upper);
        } catch (IllegalArgumentException ignored) {
            try {
                return Particle.valueOf("REDSTONE".equals(upper) ? "DUST" : "REDSTONE");
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("[Mining] Particella sconosciuta: " + name);
                return null;
            }
        }
    }

    /** Colore esadecimale delle particelle (default rosso). */
    private Color resolveColor() {
        String hex = plugin.getConfig().getString("jail-mining.particles.color", "FF0000");
        try {
            int rgb = Integer.parseInt(hex.replace("#", ""), 16);
            return Color.fromRGB(rgb);
        } catch (NumberFormatException exception) {
            plugin.getLogger().warning("[Mining] Colore particella non valido: " + hex);
            return Color.RED;
        }
    }

}
