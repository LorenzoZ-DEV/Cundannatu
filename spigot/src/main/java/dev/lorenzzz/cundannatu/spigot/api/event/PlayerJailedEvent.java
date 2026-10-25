package dev.lorenzzz.cundannatu.spigot.api.event;

import dev.lorenzzz.cundannatu.api.JailPlayer;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Lanciato dopo che un giocatore e' stato registrato come detenuto.
 * Evento informativo, non cancellabile, sempre chiamato sul thread principale.
 */
public class PlayerJailedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final JailPlayer jailPlayer;

    public PlayerJailedEvent(JailPlayer jailPlayer) {
        this.jailPlayer = jailPlayer;
    }

    /** Dati del detenuto appena creato. */
    public JailPlayer getJailPlayer() {
        return jailPlayer;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
