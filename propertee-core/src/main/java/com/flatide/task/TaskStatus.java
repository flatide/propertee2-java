package com.flatide.task;

// Ported from propertee-java v1.0.0 (com.flatide.task). The v1 gson @SerializedName annotations are
// dropped — this runtime uses no gson; value()/isTransient() are unchanged, and the lowercase wire
// names are carried by value() (hosts serialize via value(), not the enum constant name).
public enum TaskStatus {
    STARTING("starting"),
    RUNNING("running"),
    COMPLETED("completed"),
    FAILED("failed"),
    KILLED("killed"),
    DETACHED("detached"),
    LOST("lost");

    private final String value;

    TaskStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public boolean isTransient() {
        return this == STARTING || this == RUNNING || this == DETACHED;
    }
}
