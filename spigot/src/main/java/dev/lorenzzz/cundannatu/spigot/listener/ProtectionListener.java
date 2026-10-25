package dev.lorenzzz.cundannatu.spigot.listener;

import dev.lorenzzz.cundannatu.spigot.JailMiningPlugin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PotionSplashEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Divieti di combattimento in prigione.
 * <p>
 * Il PvP viene annullato quando almeno uno dei due giocatori sta scontando una pena
 * (o sempre, se {@code include-free-players} e' attivo). Sono coperti sia i colpi
 * corpo a corpo sia i proiettili, perche' entrambi passano da
 * {@link EntityDamageByEntityEvent}.
 */
public class ProtectionListener implements Listener {

    private static final String PATH = "jail-mining.protection.";

    /** Intervallo minimo fra due messaggi "niente PvP" allo stesso giocatore. */
    private static final long MESSAGE_COOLDOWN_MILLIS = 2_000L;

    private final JailMiningPlugin plugin;

    /** Ultimo avviso inviato: evita di riempire la chat ad ogni colpo. */
    private final Map<UUID, Long> lastMessage = new ConcurrentHashMap<>();

    public ProtectionListener(JailMiningPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isPvpDisabled() {
        return plugin.getConfig().getBoolean(PATH + "disable-pvp", true);
    }

    /** true se il divieto vale anche fra giocatori non detenuti. */
    private boolean includeFreePlayers() {
        return plugin.getConfig().getBoolean(PATH + "include-free-players", false);
    }

    /**
     * I detenuti non possono morire: il danno viene annullato alla radice, quindi
     * niente caduta, mob, lava o fame. Serve perche' la pena si sconta scavando e
     * una morte accidentale svuoterebbe l inventario (piccone compreso).
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAnyDamage(EntityDamageEvent event) {
        if (!plugin.getConfig().getBoolean(PATH + "disable-damage", true)) {
            return;
        }
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (!plugin.isJailed(player.getUniqueId())) {
            return;
        }
        event.setCancelled(true);

        // Il vuoto e' l unico caso in cui annullare il danno non basta: senza un
        // teleport il giocatore resterebbe a cadere all infinito sotto la mappa
        if (event.getCause() == EntityDamageEvent.DamageCause.VOID) {
            player.teleport(plugin.getJailSpawn());
        }
    }

    /** In prigione non si ha fame: la barra resta piena. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent event) {
        if (!plugin.getConfig().getBoolean(PATH + "disable-hunger", true)) {
            return;
        }
        if (!(event.getEntity() instanceof Player player) || !plugin.isJailed(player.getUniqueId())) {
            return;
        }
        // Solo il calo viene bloccato: mangiare resta possibile
        if (event.getFoodLevel() < player.getFoodLevel()) {
            event.setCancelled(true);
            event.setFoodLevel(20);
            player.setSaturation(20.0F);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!isPvpDisabled()) {
            return;
        }
        if (!(event.getEntity() instanceof Player victim)) {
            return; // il danno verso mob e altre entita' non ci riguarda
        }
        Player attacker = resolveAttacker(event);
        if (attacker == null || attacker.equals(victim)) {
            return;
        }
        if (attacker.hasPermission("jailmining.bypass")) {
            return;
        }
        if (!involvesJailed(attacker, victim)) {
            return;
        }

        event.setCancelled(true);
        warn(attacker);
    }

    /** Le pozioni negative lanciate addosso agli altri sono PvP a tutti gli effetti. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPotionSplash(PotionSplashEvent event) {
        if (!isPvpDisabled()) {
            return;
        }
        if (!(event.getPotion().getShooter() instanceof Player thrower)
                || thrower.hasPermission("jailmining.bypass")) {
            return;
        }
        for (org.bukkit.entity.LivingEntity affected : event.getAffectedEntities()) {
            if (affected instanceof Player target && !target.equals(thrower)
                    && involvesJailed(thrower, target)) {
                // Annulliamo l effetto solo sugli altri giocatori, non sul lanciatore
                event.setIntensity(target, 0.0D);
            }
        }
    }

    /**
     * Ricava il giocatore all origine del danno, seguendo anche i proiettili
     * (frecce, perle, tridenti).
     */
    private Player resolveAttacker(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player direct) {
            return direct;
        }
        if (event.getDamager() instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player player) {
                return player;
            }
        }
        return null;
    }

    /** true se il divieto si applica a questa coppia di giocatori. */
    private boolean involvesJailed(Player attacker, Player victim) {
        if (includeFreePlayers()) {
            return true;
        }
        return plugin.isJailed(attacker.getUniqueId()) || plugin.isJailed(victim.getUniqueId());
    }

    /** Avviso all attaccante, con throttle per non intasare la chat. */
    private void warn(Player attacker) {
        long now = System.currentTimeMillis();
        Long previous = lastMessage.get(attacker.getUniqueId());
        if (previous != null && now - previous < MESSAGE_COOLDOWN_MILLIS) {
            return;
        }
        lastMessage.put(attacker.getUniqueId(), now);
        plugin.send(attacker, "no-pvp");
    }
}
