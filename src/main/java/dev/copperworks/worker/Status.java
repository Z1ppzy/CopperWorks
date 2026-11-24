package dev.copperworks.worker;

/** Что голем делает прямо сейчас — показывается в диалоге. */
public enum Status {
    STARTING("starting"),
    WORKING("working"),
    IDLE("idle"),
    NOTHING_TO_SORT("nothing-to-sort"),
    NOTHING_RIPE("nothing-ripe"),
    STORAGE_FULL("storage-full"),
    NO_POST("no-post"),
    NO_TARGET("no-target"),
    TOO_FAR("too-far"),
    OTHER_WORLD("other-world"),
    WAITING_CARGO("waiting-cargo"),
    NOTHING_TO_SEND("nothing-to-send"),
    ON_TRIP("on-trip"),
    RETURNING("returning"),
    DISABLED("disabled");

    private final String key;

    Status(String key) {
        this.key = key;
    }

    public String messageKey() {
        return "status." + key;
    }
}
