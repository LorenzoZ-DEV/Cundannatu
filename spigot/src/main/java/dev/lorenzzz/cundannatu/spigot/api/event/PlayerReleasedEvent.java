package dev.lorenzzz.cundannatu.spigot.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Lanciato dopo la liberazione di un detenuto (sban LiteBans + pulizia database).
 * Evento informativo, non cancellabile, sempre chiamato sul thread principale.
 */
public class PlayerReleasedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID uuid;
    private final Reason reason;

    public PlayerReleasedEvent(UUID uuid, Reason reason) {
        this.uuid = uuid;
        this.reason = reason;
    }

    public UUID getUuid() {
        return uuid;
    }

    /** Motivo della liberazione. */
    public Reason getReason() {
        return reason;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    /** Come il detenuto ha lasciato la prigione. */
    public enum Reason {
        /** Raggiunti i crediti necessari. */
        CREDITS,
        /** Tempo di pena esaurito. */
        TIME_SERVED,
        /** Liberazione manuale di uno staffer. */
        ADMIN,
        /**
         * Sban arrivato dall esterno (staff su un altro server, acquisto sullo store,
         * appeal accolto): rilevato dal watchdog, non richiesto dal plugin.
         */
        PARDONED
    }
}
