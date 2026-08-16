package opca.messages;

public final class MessageTypes {
    private MessageTypes() {}

    public static final int CLIENT_REQUEST = 10;
    public static final int CLIENT_RESPONSE = 11;

    public static final int READ_PATH_REQUEST = 20;
    public static final int READ_PATH_RESPONSE = 21;
    public static final int WRITE_BACK_REQUEST = 22;
    public static final int WRITE_BACK_ACK = 23;

    public static final int INIT_REQUEST = 30;
    public static final int INIT_RESPONSE = 31;

    public static final int STATE_CHANGE_NOTICE = 40;

    public static final int DRAIN_REQUEST = 50;
    public static final int DRAIN_RESPONSE = 51;
}
