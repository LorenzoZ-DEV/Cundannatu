package dev.lorenzzz.cundannatu.spigot.mining;

import dev.lorenzzz.cundannatu.spigot.JailMiningPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rigenera i blocchi di carbone dopo che sono stati rotti.
 * <p>
 * Il blocco viene sostituito subito con un blocco "vuoto" (di default STONE) e
 * ripristinato dopo il ritardo configurato, cosi la miniera non si esaurisce mai.
 * I blocchi in attesa sono tracciati in memoria e vengono ripristinati tutti in
 * caso di reload o spegnimento del server.
 */
public class OreRegenManager {

    private static final String CONFIG_PATH = "jail-mining.ore-regen.";

    private final JailMiningPlugin plugin;

    /** Blocchi in attesa di rigenerazione: posizione -> materiale originale. */
    private final Map<Location, Material> pending = new ConcurrentHashMap<>();

    public OreRegenManager(JailMiningPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isEnabled() {
        return plugin.getConfig().getBoolean(CONFIG_PATH + "enabled", true);
    }

    /** true se i blocchi rotti devono droppare gli item. */
    public boolean shouldDropItems() {
        return plugin.getConfig().getBoolean(CONFIG_PATH + "drop-items", false);
    }

    /**
     * Sostituisce il blocco appena rotto e ne programma il ripristino.
     * Da eseguire sul thread principale.
     */
    public void scheduleRegen(Block block) {
        if (!isEnabled()) {
            return;
        }
        Location location = block.getLocation();
        Material original = block.getType();

        // Se il blocco e' gia' in coda non lo processiamo due volte
        if (pending.putIfAbsent(location, original) != null) {
            return;
        }

        // La sostituzione va fatta al tick successivo: durante BlockBreakEvent il server
        // mette comunque AIR al posto del blocco rotto e sovrascriverebbe il nostro setType
        Material replacement = replacementMaterial();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (pending.containsKey(location) && location.getWorld() != null) {
                location.getWorld().getBlockAt(location).setType(replacement, false);
            }
        });

        long delay = Math.max(1L, plugin.getConfig().getLong(CONFIG_PATH + "delay-seconds", 5L) * 20L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> restore(location), delay);
    }

    /** Ripristina un singolo blocco. */
    private void restore(Location location) {
        Material original = pending.remove(location);
        if (original == null) {
            return;
        }
        World world = location.getWorld();
        if (world == null) {
            return;
        }
        world.getBlockAt(location).setType(original, false);
    }

    /**
     * Ripristina immediatamente tutti i blocchi in attesa.
     * Chiamato allo spegnimento per non lasciare buchi nella miniera.
     */
    public void restoreAll() {
        if (pending.isEmpty()) {
            return;
        }
        int restored = 0;
        for (Map.Entry<Location, Material> entry : pending.entrySet()) {
            World world = entry.getKey().getWorld();
            if (world != null) {
                world.getBlockAt(entry.getKey()).setType(entry.getValue(), false);
                restored++;
            }
        }
        pending.clear();
        plugin.getLogger().info("[OreRegen] Ripristinati " + restored + " blocchi in attesa");
    }

    /** Numero di blocchi attualmente sostituiti. */
    public int getPendingCount() {
        return pending.size();
    }

    private Material replacementMaterial() {
        String name = plugin.getConfig().getString(CONFIG_PATH + "replacement-block", "STONE");
        try {
            Material material = Material.valueOf(name.toUpperCase(Locale.ROOT));
            if (material.isBlock()) {
                return material;
            }
        } catch (IllegalArgumentException ignored) {
            // gestito sotto
        }
        plugin.getLogger().warning("[OreRegen] Blocco sostitutivo non valido: " + name + ", uso STONE");
        return Material.STONE;
    }
}
