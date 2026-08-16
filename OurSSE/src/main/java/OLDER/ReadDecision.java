package oram.client;

public class ReadDecision {
    private final int pid;
    private final boolean randomPath;

    public ReadDecision(int pid, boolean randomPath) {
        this.pid = pid;
        this.randomPath = randomPath;
    }

    public int getPid() {
        return pid;
    }

    public boolean isRandomPath() {
        return randomPath;
    }
}
