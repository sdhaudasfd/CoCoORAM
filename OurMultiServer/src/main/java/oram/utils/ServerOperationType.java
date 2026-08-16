package oram.utils;

public enum ServerOperationType {
    INIT_GBMP,
    ROUND1_QUERY,
    ROUND2_READ_PATH,
    ROUND2_READ_PATH_PAIR,
    ROUND3_UPDATE_CUR,
    ROUND3_SUBMIT_EVICTION,
    ROUND3_COMMIT_EVICTION;

    public static final ServerOperationType[] values = values();

    public static ServerOperationType getOperation(int ordinal) {
        return values[ordinal];
    }
}
