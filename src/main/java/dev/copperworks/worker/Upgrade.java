package dev.copperworks.worker;

public enum Upgrade {
    SPEED("speed"),
    CAPACITY("capacity"),
    RANGE("range");

    public static final int MAX_LEVEL = 3;

    private final String id;

    Upgrade(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }
}
