package taostore.messages;

public final class MessageTypes {
    private MessageTypes() {}

    // Client <-> Proxy
    public static final int CLIENT_REQUEST = 10;
    public static final int CLIENT_RESPONSE = 11;

    // Proxy <-> Storage Server
    public static final int READ_PATH_REQUEST = 20;
    public static final int READ_PATH_RESPONSE = 21;
    public static final int WRITE_BACK_REQUEST = 22;
    public static final int WRITE_BACK_ACK = 23;

    // Proxy internal init
    public static final int INIT_ORAM_REQUEST = 30;
    public static final int INIT_ORAM_RESPONSE = 31;

    // Benchmark control
    public static final int DRAIN_REQUEST = 50;
    public static final int DRAIN_RESPONSE = 51;
}
