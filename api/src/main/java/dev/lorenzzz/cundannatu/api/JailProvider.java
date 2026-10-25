package dev.lorenzzz.cundannatu.api;

/**
 * Punto di accesso statico al {@link JailService}.
 * <p>
 * Il servizio viene registrato dal plugin backend in fase di enable e rimosso
 * al disable, quindi va sempre recuperato al momento dell uso e mai memorizzato
 * in un campo statico.
 *
 * <pre>{@code
 * if (JailProvider.isAvailable()) {
 *     JailProvider.get().getJailedPlayer(uuid).thenAccept(optional -> ...);
 * }
 * }</pre>
 */
public final class JailProvider {

    private static JailService service;

    private JailProvider() {
    }

    /** Servizio attivo. */
    public static JailService get() {
        if (service == null) {
            throw new IllegalStateException("JailService non ancora registrato: il plugin JailMining e' attivo?");
        }
        return service;
    }

    /** true se il servizio e' stato registrato. */
    public static boolean isAvailable() {
        return service != null;
    }

    /** Riservato all implementazione: registra il servizio. */
    public static void register(JailService implementation) {
        service = implementation;
    }

    /** Riservato all implementazione: rimuove il servizio. */
    public static void unregister() {
        service = null;
    }
}
