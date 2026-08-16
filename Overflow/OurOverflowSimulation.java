import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class OurOverflowSimulation {
    private static final int[] CLIENT_VALUES = {1, 5, 10, 15, 20, 30, 40, 50};
    private static final int[] ZETA_BID_EXPONENTS = {18};
    private static final int[] ZETA_BUCKET_SIZES = {2, 3, 4};
    private static final int[] ZETA_COMPETITION_SIZES = {1, 2, 3, 4};
    private static final int[] LOGN_BID_EXPONENTS = {14, 16, 18, 20};
    private static final int[] LOGN_BUCKET_SIZES = {3};
    private static final int[] LOGN_COMPETITION_SIZES = {1};
    private static final int[] ROOT_THRESHOLDS = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 16, 32};
    private static final long DEFAULT_QUERY_BUDGET = 1_000_000_000L;
    private static final long DEFAULT_SEED = 20260814L;
    
    private static final int DUMMY_BID = -1;
    private static final int DUMMY_PID = -1;
    private static final int DUMMY_SEQ = -1;

    public static void main(String[] args) {
        Config config = Config.parse(args);
        int[] bidExponentValues = config.mode.equals("zeta")
                ? ZETA_BID_EXPONENTS : LOGN_BID_EXPONENTS;
        int[] bucketSizes = config.mode.equals("zeta")
                ? ZETA_BUCKET_SIZES : LOGN_BUCKET_SIZES;
        int[] competitionSegmentSizes = config.mode.equals("zeta")
                ? ZETA_COMPETITION_SIZES : LOGN_COMPETITION_SIZES;

        int defaultWorkers = config.mode.equals("zeta")
                ? Math.min(4, Runtime.getRuntime().availableProcessors())
                : 1;
        int workerCount = config.workers == null ? defaultWorkers : config.workers;
        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        CompletionService<CsvRow> completionService = new ExecutorCompletionService<>(executor);
        int taskCount = 0;

        StringBuilder header = new StringBuilder();
        header.append("bidExponent,totalQueryBudget,timesteps,c,bucketSize,competitionBucketSize,totalQueries");
        for (int threshold : ROOT_THRESHOLDS) {
            header.append(",p(rootDemand>").append(threshold).append(")");
        }
        header.append(",avgRootDemand,maxRootDemand\n");

        Path output = config.output;
        try {
            Path parent = output.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(output, header.toString().getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create CSV results file", e);
        }

        System.out.println("Mode: " + config.mode);
        System.out.println("Query budget per configuration: " + config.queryBudget);
        System.out.println("Workers: " + workerCount);
        System.out.println("Seed: " + config.seed);

        for (int bidExponent : bidExponentValues) {
            if (bidExponent <= 0 || bidExponent >= 31) {
                throw new IllegalArgumentException("bidExponent must be in [1, 30]");
            }
            for (int c : CLIENT_VALUES) {
                long timestepCount = Math.max(1L, config.queryBudget / c);
                if (timestepCount > Integer.MAX_VALUE) {
                    throw new IllegalArgumentException("Query budget produces too many timesteps");
                }
                int timesteps = (int) timestepCount;
                for (int bucketSize : bucketSizes) {
                    for (int competitionBucketSize : competitionSegmentSizes) {
                        final int taskBidExponent = bidExponent;
                        final int taskC = c;
                        final int taskTimesteps = timesteps;
                        final int taskBucketSize = bucketSize;
                        final int taskCompetitionBucketSize = competitionBucketSize;
                        completionService.submit(new Callable<CsvRow>() {
                            @Override
                            public CsvRow call() {
                                ScenarioResult result = runScenario(
                                        taskC,
                                        taskBidExponent,
                                        taskBucketSize,
                                        taskCompetitionBucketSize,
                                        taskTimesteps,
                                        config.seed
                                );
                                return buildCsvRow(
                                        taskBidExponent,
                                        taskTimesteps,
                                        taskC,
                                        taskBucketSize,
                                        taskCompetitionBucketSize,
                                        config.queryBudget,
                                        result
                                );
                            }
                        });
                        taskCount++;
                    }
                }
            }
        }

        executor.shutdown();

        try {
            try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8, StandardOpenOption.APPEND)) {
                for (int i = 0; i < taskCount; i++) {
                    Future<CsvRow> future = completionService.take();
                    CsvRow row = future.get();
                    writer.write(row.line);
                    writer.newLine();
                    writer.flush();
                }
            }
            System.out.println("Saved results to " + output.toAbsolutePath());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for simulation tasks", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Simulation task failed", e.getCause());
        } catch (Exception e) {
            throw new RuntimeException("Failed to write CSV results", e);
        }
    }

    private static ScenarioResult runScenario(int c,
                                              int bidExponent,
                                              int bucketSize,
                                              int competitionBucketSize,
                                              int timesteps,
                                              long seed) {
        int treeHeight = bidExponent + 1;
        int leafCount = 1 << bidExponent;
        int bidSpace = leafCount;
        int treeSize = (1 << treeHeight) - 1;

        long scenarioSeed = mixSeed(seed, c, bucketSize, competitionBucketSize, bidExponent);
        Random random = new Random(scenarioSeed);

        int[] bucketCapacities = new int[treeSize];
        int[][] treeBid = new int[treeSize][];
        int[][] treePid = new int[treeSize][];
        int[][] treeSeq = new int[treeSize][];

        for (int bucketId = 1; bucketId < treeSize; bucketId++) { // bucketId starts from 1 since 0 is the root
            int level = levelOfBucket(bucketId); // bucketID: 0, 1, 2, ...; level: 0, 1, 2, ...
            int capacity = bucketCapacity(level, c, bucketSize, competitionBucketSize);
            bucketCapacities[bucketId] = capacity;
            treeBid[bucketId] = new int[capacity];
            treePid[bucketId] = new int[capacity];
            treeSeq[bucketId] = new int[capacity];
            fillDummy(treeBid[bucketId], treePid[bucketId], treeSeq[bucketId]);
        }

        int[][] rootBid = new int[c][];
        int[][] rootPid = new int[c][];
        int[][] rootSeq = new int[c][];
        int[] rootSize = new int[c];
        for (int slot = 0; slot < c; slot++) { // root initially has capacity for 4 blocks for each slot, will grow as needed
            rootBid[slot] = new int[4];
            rootPid[slot] = new int[4];
            rootSeq[slot] = new int[4];
            fillDummy(rootBid[slot], rootPid[slot], rootSeq[slot]);
            rootSize[slot] = 0;
        }

        int[] mapPid = new int[bidSpace];
        int[] mapSeq = new int[bidSpace];


        for (int bid = 0; bid < bidSpace; bid++) {
            boolean placed = false;
            int chosenPid = -1;

            for (int attempt = 0; attempt < 100_000; attempt++) {
                int pid = random.nextInt(leafCount);
                if (placeInitialBlock(treeBid, treePid, treeSeq, treeHeight, pid, bid)) {
                    chosenPid = pid;
                    placed = true;
                    break;
                }
            }

            if (!placed) {
                throw new IllegalStateException(
                        "Failed to place initial block bid=" + bid + " after many retries"
                );
            }

            mapPid[bid] = chosenPid;
            mapSeq[bid] = 0;
        }

        int[] rlBid = new int[c];
        int[] rlPid = new int[c];
        int[] rlSeq = new int[c];

        int[] roundMapPid = new int[bidSpace];
        int[] roundMapSeq = new int[bidSpace];

        int[][] rebuiltRootBid = new int[c][];
        int[][] rebuiltRootPid = new int[c][];
        int[][] rebuiltRootSeq = new int[c][];
        int[] rebuiltRootSize = new int[c]; // each slot's root size

        int[][][] rebuiltBid = new int[c][treeHeight][];
        int[][][] rebuiltPid = new int[c][treeHeight][];
        int[][][] rebuiltSeq = new int[c][treeHeight][];

        long totalQueries = 0L;
        long totalRootDemand = 0L; // total root consumption across all queries
        int maxRootDemand = 0; // max root consumption
        long[] rootDemandGreaterCounts = new long[ROOT_THRESHOLDS.length];
        long seqCounter = 0L;

        int progressStep = Math.max(1, timesteps / 100);

        for (int timestep = 0; timestep < timesteps; timestep++) {
            if (timestep == 0 || timestep == timesteps - 1 || ((timestep + 1) % progressStep) == 0) {
                printScenarioProgress(c, bucketSize, competitionBucketSize, timestep + 1, timesteps);
            }

            System.arraycopy(mapPid, 0, roundMapPid, 0, bidSpace);
            System.arraycopy(mapSeq, 0, roundMapSeq, 0, bidSpace);

            int[] assignSeq = new int[c];
            int[] assignPidE = new int[c];

            for (int slot = 0; slot < c; slot++) {
                int bid = random.nextInt(bidSpace);
                int newPid = random.nextInt(leafCount);
                int newSeq = (int) (++seqCounter);

                rlBid[slot] = bid;
                rlPid[slot] = newPid;
                rlSeq[slot] = newSeq;

                roundMapPid[bid] = newPid;
                roundMapSeq[bid] = newSeq;

                int seq = timestep * c + slot;
                assignSeq[slot] = seq;
                assignPidE[slot] = digitReverse(seq % leafCount, treeHeight - 1);
            }

            for (int slot = 0; slot < c; slot++) {
                int pidE = assignPidE[slot];
                int[] pathBucketIds = computePathBucketIds(pidE, treeHeight);

                rebuiltRootSize[slot] = 0;
                ensureRootCapacity(rebuiltRootBid, rebuiltRootPid, rebuiltRootSeq, slot, 4); // Extend root capacity by doubles if needed
                fillDummy(rebuiltRootBid[slot], rebuiltRootPid[slot], rebuiltRootSeq[slot]);

                for (int level = 1; level < treeHeight; level++) {
                    int capacity = bucketCapacity(level, c, bucketSize, competitionBucketSize);
                    if (rebuiltBid[slot][level] == null || rebuiltBid[slot][level].length != capacity) {
                        rebuiltBid[slot][level] = new int[capacity];
                        rebuiltPid[slot][level] = new int[capacity];
                        rebuiltSeq[slot][level] = new int[capacity];
                    }
                    fillDummy(rebuiltBid[slot][level], rebuiltPid[slot][level], rebuiltSeq[slot][level]);
                }

                int maxCandidates = rootTotalSize(rootSize) + totalPathSlots(bucketCapacities, pathBucketIds) + c;
                int[] candBid = new int[maxCandidates];
                int[] candPid = new int[maxCandidates];
                int[] candSeq = new int[maxCandidates];
                int candCount = 0;

                for (int owner = 0; owner < c; owner++) {
                    for (int i = 0; i < rootSize[owner]; i++) {
                        candBid[candCount] = rootBid[owner][i];
                        candPid[candCount] = rootPid[owner][i];
                        candSeq[candCount] = rootSeq[owner][i];
                        candCount++;
                    }
                }

                for (int level = 1; level < treeHeight; level++) {
                    int bucketId = pathBucketIds[level];
                    int[] bidArr = treeBid[bucketId];
                    int[] pidArr = treePid[bucketId];
                    int[] seqArr = treeSeq[bucketId];
                    for (int i = 0; i < bidArr.length; i++) {
                        candBid[candCount] = bidArr[i];
                        candPid[candCount] = pidArr[i];
                        candSeq[candCount] = seqArr[i];
                        candCount++;
                    }
                }

                for (int i = 0; i < c; i++) {
                    candBid[candCount] = rlBid[i];
                    candPid[candCount] = rlPid[i];
                    candSeq[candCount] = rlSeq[i];
                    candCount++;
                }

                int[] latestBid = new int[candCount];
                int[] latestPid = new int[candCount];
                int[] latestSeq = new int[candCount];
                int latestCount = 0;

                for (int i = 0; i < candCount; i++) {
                    int bid = candBid[i];
                    int pid = candPid[i];
                    int seq = candSeq[i];
                    if (bid == DUMMY_BID) {
                        continue;
                    }
                    if (bid < 0 || bid >= bidSpace) {
                        continue;
                    }
                    if (pid != roundMapPid[bid] || seq != roundMapSeq[bid]) {
                        continue;
                    }
                    if (!isOwned(pid, assignSeq[slot], slot, c, treeHeight, leafCount)) {
                        continue;
                    }

                    boolean seen = false;
                    for (int j = 0; j < latestCount; j++) {
                        if (latestBid[j] == bid) {
                            seen = true;
                            if (seq > latestSeq[j]) {
                                latestPid[j] = pid;
                                latestSeq[j] = seq;
                            }
                            break;
                        }
                    }
                    if (!seen) {
                        latestBid[latestCount] = bid;
                        latestPid[latestCount] = pid;
                        latestSeq[latestCount] = seq;
                        latestCount++;
                    }
                }

                for (int i = 0; i < latestCount; i++) {
                    int bid = latestBid[i];
                    int pid = latestPid[i];
                    int seq = latestSeq[i];
                    int deepestLevel = deepestCommonLevel(pid, pidE, treeHeight);
                    boolean placed = false;

                    for (int level = deepestLevel; level >= 1; level--) {
                        int start = 0;
                        int end = rebuiltBid[slot][level].length;
                        if (isCompetitionLevel(level, c)) {
                            int rank = contenderRankInBucket(assignSeq[slot], slot, pidE, level, treeHeight, leafCount);
                            start = rank * competitionBucketSize;
                            end = start + competitionBucketSize;
                        }

                        for (int pos = start; pos < end; pos++) {
                            if (rebuiltBid[slot][level][pos] == DUMMY_BID) {
                                rebuiltBid[slot][level][pos] = bid;
                                rebuiltPid[slot][level][pos] = pid;
                                rebuiltSeq[slot][level][pos] = seq;
                                placed = true;
                                break;
                            }
                        }
                        if (placed) {
                            break;
                        }
                    }

                    if (!placed) {
                        int size = rebuiltRootSize[slot];
                        ensureRootCapacity(rebuiltRootBid, rebuiltRootPid, rebuiltRootSeq, slot, size + 1);
                        rebuiltRootBid[slot][size] = bid;
                        rebuiltRootPid[slot][size] = pid;
                        rebuiltRootSeq[slot][size] = seq;
                        rebuiltRootSize[slot] = size + 1;
                    }
                }

                int rootDemand = rebuiltRootSize[slot];
                totalQueries++;
                totalRootDemand += rootDemand;
                if (rootDemand > maxRootDemand) {
                    maxRootDemand = rootDemand;
                }
                for (int i = 0; i < ROOT_THRESHOLDS.length; i++) {
                    if (rootDemand > ROOT_THRESHOLDS[i]) {
                        rootDemandGreaterCounts[i]++;
                    }
                }
            }

            for (int slot = 0; slot < c; slot++) {
                int pidE = assignPidE[slot];
                int[] pathBucketIds = computePathBucketIds(pidE, treeHeight);

                ensureRootCapacity(rootBid, rootPid, rootSeq, slot, rebuiltRootSize[slot]);
                fillDummy(rootBid[slot], rootPid[slot], rootSeq[slot]);
                for (int i = 0; i < rebuiltRootSize[slot]; i++) {
                    rootBid[slot][i] = rebuiltRootBid[slot][i];
                    rootPid[slot][i] = rebuiltRootPid[slot][i];
                    rootSeq[slot][i] = rebuiltRootSeq[slot][i];
                }
                rootSize[slot] = rebuiltRootSize[slot];

                for (int level = 1; level < treeHeight; level++) {
                    int bucketId = pathBucketIds[level];
                    int start = 0;
                    int end = treeBid[bucketId].length;
                    if (isCompetitionLevel(level, c)) {
                        int rank = contenderRankInBucket(assignSeq[slot], slot, pidE, level, treeHeight, leafCount);
                        start = rank * competitionBucketSize;
                        end = start + competitionBucketSize;
                    }
                    for (int pos = start; pos < end; pos++) {
                        treeBid[bucketId][pos] = rebuiltBid[slot][level][pos];
                        treePid[bucketId][pos] = rebuiltPid[slot][level][pos];
                        treeSeq[bucketId][pos] = rebuiltSeq[slot][level][pos];
                    }
                }
            }

            int[] tmpPid = mapPid;
            mapPid = roundMapPid;
            roundMapPid = tmpPid;

            int[] tmpSeq = mapSeq;
            mapSeq = roundMapSeq;
            roundMapSeq = tmpSeq;
        }

        printScenarioProgress(c, bucketSize, competitionBucketSize, timesteps, timesteps);
        System.out.println();

        return new ScenarioResult(totalQueries, rootDemandGreaterCounts, totalRootDemand, maxRootDemand);
    }

    private static CsvRow buildCsvRow(int bidExponent,
                                      int timesteps,
                                      int c,
                                      int bucketSize,
                                      int competitionBucketSize,
                                      long queryBudget,
                                      ScenarioResult result) {
        StringBuilder row = new StringBuilder();
        row.append(bidExponent).append(",")
                .append(queryBudget).append(",")
                .append(timesteps).append(",")
                .append(c).append(",")
                .append(bucketSize).append(",")
                .append(competitionBucketSize).append(",")
                .append(result.totalQueries);

        for (int i = 0; i < ROOT_THRESHOLDS.length; i++) {
            double probability = result.totalQueries == 0
                    ? 0.0
                    : ((double) result.rootDemandGreaterCounts[i]) / ((double) result.totalQueries);
            row.append(",").append(probability);
        }

        double avgRootDemand = result.totalQueries == 0
                ? 0.0
                : ((double) result.totalRootDemand) / ((double) result.totalQueries);
        row.append(",").append(avgRootDemand)
                .append(",").append(result.maxRootDemand);

        return new CsvRow(bidExponent, c, bucketSize, competitionBucketSize, row.toString());
    }

    private static final class Config {
        private final String mode;
        private final long queryBudget;
        private final long seed;
        private final Integer workers;
        private final Path output;

        private Config(String mode, long queryBudget, long seed, Integer workers, Path output) {
            this.mode = mode;
            this.queryBudget = queryBudget;
            this.seed = seed;
            this.workers = workers;
            this.output = output;
        }

        private static Config parse(String[] args) {
            String mode = "zeta";
            long queryBudget = DEFAULT_QUERY_BUDGET;
            long seed = DEFAULT_SEED;
            Integer workers = null;
            Path output = null;

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if ("--help".equals(arg) || "-h".equals(arg)) {
                    printUsage();
                    System.exit(0);
                }
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("Missing value for " + arg);
                }
                String value = args[++i];
                switch (arg) {
                    case "--mode":
                        mode = value;
                        break;
                    case "--queries":
                        queryBudget = Long.parseLong(value);
                        break;
                    case "--seed":
                        seed = Long.parseLong(value);
                        break;
                    case "--workers":
                        workers = Integer.parseInt(value);
                        break;
                    case "--output":
                        output = Paths.get(value);
                        break;
                    default:
                        throw new IllegalArgumentException("Unknown option: " + arg);
                }
            }

            if (!"zeta".equals(mode) && !"logn".equals(mode)) {
                throw new IllegalArgumentException("--mode must be zeta or logn");
            }
            if (queryBudget <= 0L) {
                throw new IllegalArgumentException("--queries must be positive");
            }
            if (workers != null && workers <= 0) {
                throw new IllegalArgumentException("--workers must be positive");
            }
            if (output == null) {
                output = Paths.get("results", "overflow_by_" + mode + ".csv");
            }
            return new Config(mode, queryBudget, seed, workers, output);
        }

        private static void printUsage() {
            System.out.println("Usage: java OurOverflowSimulation [options]");
            System.out.println("  --mode zeta|logn   Sweep zeta at N=2^18, or sweep N at Z=3,zeta=1");
            System.out.println("  --queries <long>   Query budget per configuration (default: 1000000000)");
            System.out.println("  --workers <int>    Parallel configurations (default: 4 for zeta, 1 for logn)");
            System.out.println("  --seed <long>      Base random seed (default: 20260814)");
            System.out.println("  --output <path>    Result CSV path (default: results/overflow_by_<mode>.csv)");
        }
    }

    private static boolean placeInitialBlock(int[][] treeBid,
                                          int[][] treePid,
                                          int[][] treeSeq,
                                          int treeHeight,
                                          int pid,
                                          int bid) {
        int[] bucketIds = computePathBucketIds(pid, treeHeight);
        for (int i = bucketIds.length - 1; i >= 1; i--) {
            int bucketId = bucketIds[i];
            for (int slot = 0; slot < treeBid[bucketId].length; slot++) {
                if (treeBid[bucketId][slot] == DUMMY_BID) {
                    treeBid[bucketId][slot] = bid;
                    treePid[bucketId][slot] = pid;
                    treeSeq[bucketId][slot] = 0;
                    return true;
                }
            }
        }
        return false;
    }

    private static int rootTotalSize(int[] rootSize) {
        int total = 0;
        for (int size : rootSize) {
            total += size;
        }
        return total;
    }

    private static int totalPathSlots(int[] bucketCapacities, int[] pathBucketIds) {
        int total = 0;
        for (int level = 1; level < pathBucketIds.length; level++) {
            total += bucketCapacities[pathBucketIds[level]];
        }
        return total;
    }

    private static void ensureRootCapacity(int[][] bidArr, int[][] pidArr, int[][] seqArr, int slot, int needed) {
        if (bidArr[slot] == null) {
            int initialCapacity = Math.max(needed, 4);
            bidArr[slot] = new int[initialCapacity];
            pidArr[slot] = new int[initialCapacity];
            seqArr[slot] = new int[initialCapacity];
            fillDummy(bidArr[slot], pidArr[slot], seqArr[slot]);
            return;
        }

        if (bidArr[slot].length >= needed) {
            return;
        }

        int oldCapacity = bidArr[slot].length;
        int newCapacity = Math.max(needed, oldCapacity * 2);
        bidArr[slot] = Arrays.copyOf(bidArr[slot], newCapacity);
        pidArr[slot] = Arrays.copyOf(pidArr[slot], newCapacity);
        seqArr[slot] = Arrays.copyOf(seqArr[slot], newCapacity);

        for (int i = oldCapacity; i < newCapacity; i++) {
            bidArr[slot][i] = DUMMY_BID;
            pidArr[slot][i] = DUMMY_PID;
            seqArr[slot][i] = DUMMY_SEQ;
        }
    }

    private static void fillDummy(int[] bid, int[] pid, int[] seq) {
        Arrays.fill(bid, DUMMY_BID);
        Arrays.fill(pid, DUMMY_PID);
        Arrays.fill(seq, DUMMY_SEQ);
    }

    private static boolean isOwned(int blockPid,
                                   int seq,
                                   int currentSlot,
                                   int c,
                                   int treeHeight,
                                   int leafCount) {
        int timestepBaseSeq = seq - currentSlot;
        int maxCompetitionLevel = 31 - Integer.numberOfLeadingZeros(c);
        int shift = (treeHeight - 1) - maxCompetitionLevel;
        int seqPid = digitReverse(seq % leafCount, treeHeight - 1);

        if ((seqPid >> shift) != (blockPid >> shift)) {
            return false;
        }
        if (shift == 0) {
            return true;
        }
        if ((seqPid >> (shift - 1)) == (blockPid >> (shift - 1))) {
            return true;
        }

        int blockChildPrefix = blockPid >> (shift - 1);
        for (int otherSlot = 0; otherSlot < c; otherSlot++) {
            if (otherSlot == currentSlot) {
                continue;
            }
            int otherSeq = timestepBaseSeq + otherSlot;
            int otherPid = digitReverse(otherSeq % leafCount, treeHeight - 1);
            if ((otherPid >> (shift - 1)) == blockChildPrefix) {
                return false;
            }
        }
        return true;
    }

    private static int deepestCommonLevel(int a, int b, int treeHeight) {
        int x = a ^ b;
        if (x == 0) {
            return treeHeight - 1;
        }
        return (treeHeight - 2) - (31 - Integer.numberOfLeadingZeros(x));
    }

    private static boolean isCompetitionLevel(int level, int c) {
        return contendersAtLevel(level, c) > 1;
    }

    private static int contendersAtLevel(int level, int c) {
        int groups = 1 << level; // 2, 4, 8, ...
        return groups >= c ? 1 : (c + groups - 1) / groups;
    }

    private static int bucketCapacity(int level, int c, int bucketSize, int competitionBucketSize) {
        if (!isCompetitionLevel(level, c)) {
            return bucketSize;
        }
        return competitionBucketSize * contendersAtLevel(level, c);
    }

    private static int contenderRankInBucket(int seq,
                                             int slot,
                                             int pid,
                                             int level,
                                             int treeHeight,
                                             int leafCount) {
        int timestepBaseSeq = seq - slot;
        int shift = (treeHeight - 1) - level;
        int myPrefix = pid >> shift;
        int rank = 0;
        for (int otherSlot = 0; otherSlot < slot; otherSlot++) {
            int otherPid = digitReverse((timestepBaseSeq + otherSlot) & (leafCount - 1), treeHeight - 1);
            if ((otherPid >> shift) == myPrefix) {
                rank++;
            }
        }
        return rank;
    }

    private static int[] computePathBucketIds(int pid, int treeHeight) {
        int[] bucketIds = new int[treeHeight];
        int current = ((1 << (treeHeight - 1)) - 1) + pid;
        for (int level = treeHeight - 1; level >= 0; level--) {
            bucketIds[level] = current;
            current = (current - 1) >> 1;
        }
        return bucketIds;
    }

    private static int levelOfBucket(int bucketId) {
        return 31 - Integer.numberOfLeadingZeros(bucketId + 1);
    }

    private static int digitReverse(int value, int bits) {
        return Integer.reverse(value) >>> (Integer.SIZE - bits);
    }

    private static void printScenarioProgress(int c,
                                              int bucketSize,
                                              int competitionBucketSize,
                                              int completed,
                                              int total) {
        int width = 30;
        int filled = total == 0 ? width : (int) (((long) completed * width) / total);
        StringBuilder bar = new StringBuilder(width);
        for (int i = 0; i < width; i++) {
            bar.append(i < filled ? '#' : '-');
        }
        double percent = total == 0 ? 100.0 : (100.0 * completed) / total;
        System.out.print(
                "\r[c=" + c +
                        ", bucket=" + bucketSize +
                        ", comp=" + competitionBucketSize +
                        "] [" + bar + "] " +
                        String.format("%.1f", percent) + "%"
        );
    }

    private static long mixSeed(long baseSeed,
                                int c,
                                int bucketSize,
                                int competitionBucketSize,
                                int bidExponent) {
        long x = baseSeed;
        x ^= 0x9E3779B97F4A7C15L + ((long) c << 1);
        x = mix64(x);
        x ^= 0x9E3779B97F4A7C15L + ((long) bucketSize << 7);
        x = mix64(x);
        x ^= 0x9E3779B97F4A7C15L + ((long) competitionBucketSize << 13);
        x = mix64(x);
        x ^= 0x9E3779B97F4A7C15L + ((long) bidExponent << 17);
        return mix64(x);
    }

    private static long mix64(long z) {
        z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL;
        z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L;
        return z ^ (z >>> 33);
    }

    private static final class ScenarioResult {
        private final long totalQueries;
        private final long[] rootDemandGreaterCounts;
        private final long totalRootDemand;
        private final int maxRootDemand;

        private ScenarioResult(long totalQueries,
                               long[] rootDemandGreaterCounts,
                               long totalRootDemand,
                               int maxRootDemand) {
            this.totalQueries = totalQueries;
            this.rootDemandGreaterCounts = rootDemandGreaterCounts;
            this.totalRootDemand = totalRootDemand;
            this.maxRootDemand = maxRootDemand;
        }
    }

    private static final class CsvRow {
        private final int bidExponent;
        private final int c;
        private final int bucketSize;
        private final int competitionBucketSize;
        private final String line;

        private CsvRow(int bidExponent, int c, int bucketSize, int competitionBucketSize, String line) {
            this.bidExponent = bidExponent;
            this.c = c;
            this.bucketSize = bucketSize;
            this.competitionBucketSize = competitionBucketSize;
            this.line = line;
        }
    }
}
