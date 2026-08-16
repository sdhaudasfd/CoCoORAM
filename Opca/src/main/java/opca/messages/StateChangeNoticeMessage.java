package opca.messages;

import oram.common.ORAMUtils;

public class StateChangeNoticeMessage {
    private final int state;

    public StateChangeNoticeMessage(int state) {
        this.state = state;
    }

    public int getState() {
        return state;
    }

    public byte[] toBytes() {
        byte[] out = new byte[Integer.BYTES];
        ORAMUtils.serializeInteger(state, out, 0);
        return out;
    }

    public static StateChangeNoticeMessage fromBytes(byte[] input) {
        return new StateChangeNoticeMessage(ORAMUtils.deserializeInteger(input, 0));
    }
}
