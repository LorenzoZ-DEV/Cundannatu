package dev.lorenzzz.cundannatu.spigot.listener;

import dev.lorenzzz.cundannatu.spigot.JailMiningPlugin;
import dev.lorenzzz.cundannatu.common.JailPlayerData;
import dev.lorenzzz.cundannatu.spigot.util.Placeholders;
import dev.lorenzzz.cundannatu.spigot.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Gestisce l ingresso e l uscita dal server jail:
 * caricamento dati da MySQL, teleport allo spawn della prigione, messaggi di
 * entrata/uscita personalizzati, action bar e whitelist dei comandi.
 */
public class JailSpawnListener implements Listener {

    private static final String JOIN_QUIT_PATH = "jail-mining.join-quit.";

    private final JailMiningPlugin plugin;

    public JailSpawnListener(JailMiningPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        // Il messaggio di default ("player joined the game") viene soppresso: lo
        // pubblichiamo noi appena sappiamo se il giocatore sta scontando una pena
        boolean customMessages = plugin.getConfig().getBoolean(JOIN_QUIT_PATH + "enabled", true);
        if (customMessages) {
            event.setJoinMessage(null);
        }

        // SELECT asincrona: il thread principale non attende mai il database
        plugin.<JailPlayerData>newChain()
                .asyncFirst(() -> plugin.getDatabaseManager().getJailedPlayer(uuid).join())
                .syncLast(data -> {
                    if (customMessages) {
                        broadcastJoinQuit(player, data, true);
                    }
                    if (data == null) {
                        // Null check: non e' un detenuto, nessuna restrizione
                        plugin.getLogger().fine("[Join] " + player.getName() + " non risulta in jail");
                        plugin.getScoreboardUpdater().updateNow(player, null);
                        plugin.getTabListManager().updateNow(player, null);
                        return;
                    }
                    if (player.hasPermission("jailmining.bypass")) {
                        // Staff: la pena resta a database ma non viene applicata,
                        // niente teleport in prigione ne' restrizioni
                        plugin.getLogger().info("[Join] " + player.getName()
                                + " ha una pena attiva ma e' staff: nessuna restrizione applicata");
                        plugin.getScoreboardUpdater().updateNow(player, null);
                        plugin.getTabListManager().updateNow(player, null);
                        return;
                    }
                    applyJail(player, data);
                })
                .execute((finished, exception) -> {
                    if (exception != null) {
                        plugin.getLogger().log(Level.SEVERE,
                                "[Join] Errore caricando i dati di " + uuid, exception);
                    }
                });
    }

    /** Applica lo stato di detenzione: teleport, messaggi, scoreboard, tab e action bar. */
    private void applyJail(Player player, JailPlayerData data) {
        player.teleport(plugin.getJailSpawn());

        Map<String, String> placeholders = Placeholders.withJail(plugin, player, data);
        for (String line : plugin.getConfig().getStringList(JOIN_QUIT_PATH + "welcome-jailed")) {
            plugin.sendRaw(player, line, placeholders);
        }

        if (plugin.getJailPickaxe().isGivenOnJoin()) {
            plugin.getJailPickaxe().give(player);
        }
        plugin.getScoreboardUpdater().updateNow(player, data);
        plugin.getTabListManager().updateNow(player, data);
        startActionBar(player);

        plugin.getLogger().info("[Join] Detenuto " + player.getName() + " caricato: "
                + data.getFormattedRemaining() + " residui, " + data.getJailCredits() + " crediti");
    }

    /**
     * Pubblica il messaggio di entrata/uscita configurato.
     *
     * @param data dati della pena, {@code null} se il giocatore e' libero
     * @param join true per l entrata, false per l uscita
     */
    private void broadcastJoinQuit(Player player, JailPlayerData data, boolean join) {
        String key = (join ? "join-" : "quit-") + (data == null ? "free" : "jailed");
        String raw = plugin.getConfig().getString(JOIN_QUIT_PATH + key, "");
        if (raw == null || raw.isBlank()) {
            return; // stringa vuota = messaggio disattivato
        }
        Map<String, String> placeholders = data == null
                ? Placeholders.of(plugin, player)
                : Placeholders.withJail(plugin, player, data);

        // Broadcast via Adventure: i messaggi di join possono usare MiniMessage
        Component message = Text.component(plugin.applyGlobalPlaceholders(raw), placeholders);
        if (plugin.audiences() != null) {
            plugin.audiences().all().sendMessage(message);
        } else {
            Bukkit.broadcastMessage(Text.format(raw, placeholders));
        }
    }

    /** Action bar ripetuta finche' il giocatore resta detenuto. */
    private void startActionBar(Player player) {
        if (!plugin.getConfig().getBoolean("jail-mining.action-bar.enabled", true)) {
            return;
        }
        long interval = Math.max(1L, plugin.getConfig().getLong("jail-mining.action-bar.interval-ticks", 40L));
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline() || !plugin.isJailed(player.getUniqueId())) {
                    cancel();
                    return;
                }
                JailPlayerData data = plugin.getDatabaseManager().getCached(player.getUniqueId());
                Map<String, String> placeholders = Placeholders.withJail(plugin, player, data);
                String raw = plugin.applyGlobalPlaceholders(plugin.getRawMessage("action-bar"));
                plugin.audiences().player(player).sendActionBar(Text.component(raw, placeholders));
            }
        }.runTaskTimer(plugin, 20L, interval);
    }

    /** Alla disconnessione forziamo la persistenza dei dati ancora in memoria. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        JailPlayerData data = plugin.getDatabaseManager().getCached(uuid);

        if (plugin.getConfig().getBoolean(JOIN_QUIT_PATH + "enabled", true)) {
            event.setQuitMessage(null);
            broadcastJoinQuit(player, data, false);
        }

        // La board FastBoard va sempre eliminata per non lasciare riferimenti appesi
        plugin.getScoreboardUpdater().clear(player);
        plugin.getMiningGuard().forget(uuid);

        if (data != null && data.isDirty()) {
            plugin.getDatabaseManager().saveJailedPlayer(data);
        }
    }

    /** Il piccone del detenuto non puo' essere buttato via. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDropItem(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        if (!plugin.isJailed(player.getUniqueId()) || player.hasPermission("jailmining.bypass")) {
            return;
        }
        if (!plugin.getJailPickaxe().isDropPrevented()) {
            return;
        }
        if (plugin.getJailPickaxe().isJailPickaxe(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
            plugin.send(player, "pickaxe-locked");
        }
    }

    /** Il piccone non puo' nemmeno essere spostato fuori dall inventario. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!plugin.isJailed(player.getUniqueId()) || player.hasPermission("jailmining.bypass")) {
            return;
        }
        if (!plugin.getJailPickaxe().isMovePrevented()) {
            return;
        }
        ItemStack clicked = event.getCurrentItem();
        ItemStack cursor = event.getCursor();
        if (plugin.getJailPickaxe().isJailPickaxe(clicked) || plugin.getJailPickaxe().isJailPickaxe(cursor)) {
            event.setCancelled(true);
            plugin.send(player, "pickaxe-locked");
        }
    }

    /** Blocca tutti i comandi non presenti nella whitelist di config.yml. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!plugin.isJailed(player.getUniqueId())) {
            return;
        }
        if (player.hasPermission("jailmining.bypass")) {
            return;
        }

        String message = event.getMessage().toLowerCase(Locale.ROOT);
        String command = message.split(" ")[0];

        // /jailinfo e /jailhelp restano sempre disponibili: servono al detenuto per
        // capire la sua situazione e come uscirne
        List<String> always = List.of("/jailinfo", "/jailhelp", "/perche", "/banhelp", "/comefunziona");
        List<String> allowed = plugin.getAllowedCommands();
        boolean permitted = always.contains(command) || allowed.stream()
                .map(entry -> entry.toLowerCase(Locale.ROOT))
                .anyMatch(entry -> entry.equals(command));

        if (!permitted) {
            event.setCancelled(true);
            String list = String.join(", ", allowed) + ", /jailinfo, /jailhelp";
            plugin.send(player, "command-blocked", Map.of("commands", list));
        }
    }
}
