package io.github.dreamlike;


public enum PollerMode {

    SYSTEM_POLLER,
    VIRTUAL_THREAD_POLLER,
    PER_CARRIER_VIRTUAL_THREAD_POLLER,
    AUTO;
    public static final PollerMode CURRENT_TYPE;
    static {
        String pollerMode = System.getProperty("jdk.pollerMode");
        CURRENT_TYPE = switch (pollerMode) {
            case null -> AUTO;
            case "1" -> SYSTEM_POLLER;
            case "2" -> VIRTUAL_THREAD_POLLER;
            case "3" -> PER_CARRIER_VIRTUAL_THREAD_POLLER;
            default -> throw new RuntimeException(pollerMode + " is not a valid polling mode");
        };
    }
}
