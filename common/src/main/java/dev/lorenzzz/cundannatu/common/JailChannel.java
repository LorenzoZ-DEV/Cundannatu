package dev.lorenzzz.cundannatu.common;

/**
 * Costanti del canale plugin condiviso fra proxy e backend.
 */
public final class JailChannel {

    /** Namespace del canale. */
    public static final String NAMESPACE = "jailmining";

    /** Nome del canale: {@code jailmining:data}. */
    public static final String NAME = NAMESPACE + ":data";

    private JailChannel() {
    }
}
