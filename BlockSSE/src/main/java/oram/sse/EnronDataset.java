package oram.sse;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;

public final class EnronDataset {
    private final Map<Integer, List<Integer>> postings;
    private final int documentCount;
    private final long pairCount;

    private EnronDataset(Map<Integer, List<Integer>> postings,
                         int documentCount,
                         long pairCount) {
        this.postings = postings;
        this.documentCount = documentCount;
        this.pairCount = pairCount;
    }

    public static EnronDataset load(Path path) throws IOException {
        Map<Integer, List<Integer>> postings = new HashMap<>();
        int documentCount = 0;
        long pairCount = 0L;

        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.trim().isEmpty()) {
                    continue;
                }

                StringTokenizer tokenizer = new StringTokenizer(line);
                if (!tokenizer.hasMoreTokens()) {
                    continue;
                }

                int documentId;
                try {
                    documentId = Integer.parseInt(tokenizer.nextToken());
                } catch (NumberFormatException e) {
                    throw new IOException("Invalid document id at line " + lineNumber, e);
                }

                documentCount++;
                while (tokenizer.hasMoreTokens()) {
                    int keywordId = parseKeywordId(tokenizer.nextToken(), lineNumber);
                    postings.computeIfAbsent(keywordId, ignored -> new ArrayList<>()).add(documentId);
                    pairCount++;
                }
            }
        }

        for (Map.Entry<Integer, List<Integer>> entry : postings.entrySet()) {
            entry.setValue(Collections.unmodifiableList(entry.getValue()));
        }

        return new EnronDataset(Collections.unmodifiableMap(postings), documentCount, pairCount);
    }

    private static int parseKeywordId(String token, int lineNumber) throws IOException {
        if (!token.startsWith("kw") || token.length() <= 2) {
            throw new IOException("Invalid keyword token '" + token + "' at line " + lineNumber);
        }
        try {
            return Integer.parseInt(token.substring(2));
        } catch (NumberFormatException e) {
            throw new IOException("Invalid keyword token '" + token + "' at line " + lineNumber, e);
        }
    }

    public Map<Integer, List<Integer>> getPostings() {
        return postings;
    }

    public List<Integer> getPostingList(int keywordId) {
        List<Integer> documents = postings.get(keywordId);
        return documents == null ? Collections.emptyList() : documents;
    }

    public int getDocumentCount() {
        return documentCount;
    }

    public int getKeywordCount() {
        return postings.size();
    }

    public long getPairCount() {
        return pairCount;
    }
}
