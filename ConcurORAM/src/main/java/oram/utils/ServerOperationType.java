package oram.utils;

public enum ServerOperationType {
    INIT_GBMP,
    ROUND1_QUERY,
    ROUND2_READ_PATH,
    ROUND3_UPDATE_CUR,
    ROUND3_SUBMIT_EVICTION,
    CONCUR_REGISTER_QUERY,
    CONCUR_READ_MAP_UPDATES,
    CONCUR_READ_COMMITTED_MAP_UPDATES,
    CONCUR_READ_LOGS_AND_STASHES,
    CONCUR_READ_CURRENT_DRL,
    CONCUR_READ_DATA_PATH,
    CONCUR_WRITE_QUERY_RESULT,
    CONCUR_FINALIZE_ROUND,
    CONCUR_READ_EVICTION_INPUT,
    CONCUR_READ_EVICTION_CRITICAL,
    CONCUR_SUBMIT_EVICTION,
    CONCUR_COMMIT_READY;

    public static final ServerOperationType[] values = values();

    public static ServerOperationType getOperation(int ordinal) {
        return values[ordinal];
    }
}
