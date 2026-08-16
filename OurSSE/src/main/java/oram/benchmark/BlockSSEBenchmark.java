package oram.benchmark;

public final class BlockSSEBenchmark {
    private BlockSSEBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 11) {
            ConcurrentSSEBenchmark.main(args);
            return;
        }
        if (Integer.parseInt(args[1]) != 1) {
            throw new IllegalArgumentException(
                    "The sequential BlockSSE baseline requires nClients=1"
            );
        }
        ConcurrentSSEBenchmark.main(args);
    }
}
