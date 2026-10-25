package dev.lorenzzz.cundannatu.spigot.api;

import dev.lorenzzz.cundannatu.api.BanInfo;
import dev.lorenzzz.cundannatu.api.JailPlayer;
import dev.lorenzzz.cundannatu.api.JailService;
import dev.lorenzzz.cundannatu.common.JailPlayerData;
import dev.lorenzzz.cundannatu.spigot.JailMiningPlugin;
import dev.lorenzzz.cundannatu.spigot.db.DatabaseManager;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Implementazione del {@link JailService} esposto agli altri plugin.
 * <p>
 * Fa da facciata su {@link DatabaseManager} e
 * {@link dev.lorenzzz.cundannatu.spigot.ban.BanManager}, mantenendo allineate
 * cache in memoria e righe su MySQL.
 */
public class JailServiceImpl implements JailService {

    private final JailMiningPlugin plugin;

    public JailServiceImpl(JailMiningPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public CompletableFuture<Optional<JailPlayer>> getJailedPlayer(UUID uuid) {
        return plugin.getDatabaseManager().getJailedPlayer(uuid)
                .thenApply(data -> Optional.ofNullable((JailPlayer) data));
    }

    @Override
    public Optional<JailPlayer> getCachedJailedPlayer(UUID uuid) {
        return Optional.ofNullable(plugin.getDatabaseManager().getCached(uuid));
    }

    @Override
    public boolean isJailed(UUID uuid) {
        return plugin.getDatabaseManager().getCached(uuid) != null;
    }

    @Override
    public CompletableFuture<JailPlayer> jail(UUID uuid, String reason, long durationMillis) {
        return plugin.getBanManager()
                .registerJailedPlayer(uuid, reason, durationMillis)
                .thenApply(data -> data);
    }

    @Override
    public CompletableFuture<Boolean> release(UUID uuid) {
        return plugin.getBanManager().unbanPlayer(uuid);
    }

    @Override
    public CompletableFuture<Void> addCredits(UUID uuid, int amount) {
        JailPlayerData data = plugin.getDatabaseManager().getCached(uuid);
        if (data == null) {
            // Non in cache: rileggiamo, modifichiamo e riscriviamo
            return plugin.getDatabaseManager().getJailedPlayer(uuid).thenCompose(loaded -> {
                if (loaded == null) {
                    return CompletableFuture.completedFuture(null);
                }
                loaded.addCredits(amount);
                return plugin.getDatabaseManager().updateJailCredits(uuid, loaded.getJailCredits());
            });
        }
        data.addCredits(amount);
        return plugin.getDatabaseManager().updateJailCredits(uuid, data.getJailCredits());
    }

    @Override
    public CompletableFuture<Void> reduceRemaining(UUID uuid, long millis) {
        JailPlayerData data = plugin.getDatabaseManager().getCached(uuid);
        if (data == null) {
            return plugin.getDatabaseManager().getJailedPlayer(uuid).thenCompose(loaded -> {
                if (loaded == null) {
                    return CompletableFuture.completedFuture(null);
                }
                loaded.reduceRemaining(millis);
                return plugin.getDatabaseManager().updateRemainingTime(uuid, loaded.getCurrentRemaining());
            });
        }
        data.reduceRemaining(millis);
        return plugin.getDatabaseManager().updateRemainingTime(uuid, data.getCurrentRemaining());
    }

    @Override
    public CompletableFuture<Optional<BanInfo>> getBanInfo(UUID uuid) {
        return plugin.getBanManager().getBanInfo(uuid).thenApply(Optional::ofNullable);
    }

    @Override
    public int getCreditsRequiredForFreedom() {
        return plugin.getCreditsForFreedom();
    }

    @Override
    public long getReductionPerCoalMillis() {
        return plugin.getCarboneReductionMillis();
    }

    @Override
    public String getJailServerName() {
        return plugin.getJailServerName();
    }
}
