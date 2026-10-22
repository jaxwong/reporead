package com.reporead.github;

import com.reporead.document.MarkdownRenderer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;

/**
 * Measures how long a sync's content-move reads take against real GitHub, one at a time and concurrently: the blobs of
 * one real reorganizing commit (every Markdown path it added or deleted, exact moves excluded), each read through
 * GitHubApi.blob and rendered. Opt-in and read-only: REPOREAD_MEASURE_CORPUS names a local clone of
 * REPOREAD_MEASURE_GITHUB_REPO (owner/name), REPOREAD_MEASURE_COMMIT the commit, REPOREAD_MEASURE_GITHUB_TOKEN a token
 * that can read it. Each run pauses first so the whole measurement stays under GitHub's secondary rate limit.
 */
@EnabledIfEnvironmentVariable(named = "REPOREAD_MEASURE_GITHUB_TOKEN", matches = ".+")
class BlobReadMeasurement {
    private static final List<Integer> CONCURRENCY = List.of(1, 4, 8, 16);
    private static final int ROUNDS = 3;
    private static final long PAUSE_MILLIS = 20_000;

    private final Path corpus = Path.of(System.getenv("REPOREAD_MEASURE_CORPUS"));
    private final String[] repository =
            System.getenv("REPOREAD_MEASURE_GITHUB_REPO").split("/");
    private final String commit = System.getenv("REPOREAD_MEASURE_COMMIT");
    private final String token = System.getenv("REPOREAD_MEASURE_GITHUB_TOKEN");
    private final Path out = Path.of(
            Optional.ofNullable(System.getenv("REPOREAD_MEASURE_OUT")).orElse("build/blob-read-measurement.txt"));

    record Candidate(String path, String blobSha) {}

    record Run(List<Double> sortedReadMillis, double wallMillis) {}

    @Test
    void measure() throws Exception {
        var candidates = candidates();
        var meters = new SimpleMeterRegistry();
        var requestFactory = new GitHubHttpConfiguration().githubRequestFactory();
        var github = new GitHubApi(
                GitHubHttpConfiguration.bounded(requestFactory, GitHubHttpConfiguration.MAX_RESPONSE_BYTES, meters),
                GitHubHttpConfiguration.bounded(
                        requestFactory, GitHubHttpConfiguration.MAX_TREE_RESPONSE_BYTES, meters),
                GitHubHttpConfiguration.bounded(
                        requestFactory, GitHubHttpConfiguration.MAX_IMAGE_RESPONSE_BYTES, meters),
                JsonMapper.builder().build());
        var report = new StringBuilder("Candidates in " + commit.substring(0, 7) + ": " + candidates.size() + "\n");
        // Discarded: the first read of a blob may be slower on GitHub's side than any later one.
        run(github, candidates, 8);
        for (int round = 0; round < ROUNDS; round++) {
            for (int concurrency : CONCURRENCY) {
                Thread.sleep(PAUSE_MILLIS);
                var run = run(github, candidates, concurrency);
                var reads = run.sortedReadMillis();
                report.append(String.format(
                        "round %d concurrency %2d: wall %6.0f ms; per read p50 %4.0f ms, p95 %4.0f ms, max %4.0f ms%n",
                        round,
                        concurrency,
                        run.wallMillis(),
                        reads.get(reads.size() / 2),
                        reads.get((int) ((reads.size() - 1) * 0.95)),
                        reads.getLast()));
            }
        }
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.writeString(out, report);
        System.out.println(report);
    }

    private Run run(GitHubApi github, List<Candidate> candidates, int concurrency) throws Exception {
        long start = System.nanoTime();
        var latencies = new ArrayList<Double>();
        try (var executor = Executors.newFixedThreadPool(concurrency)) {
            var futures = new ArrayList<Future<Double>>();
            for (var candidate : candidates) {
                futures.add(executor.submit(() -> {
                    long readStart = System.nanoTime();
                    byte[] bytes = github.blob(token, repository[0], repository[1], candidate.blobSha());
                    MarkdownRenderer.render(
                            new String(bytes, StandardCharsets.UTF_8), candidate.blobSha(), candidate.path());
                    return (System.nanoTime() - readStart) / 1e6;
                }));
            }
            for (var future : futures) {
                try {
                    latencies.add(future.get());
                } catch (ExecutionException failure) {
                    throw new IllegalStateException(
                            "A blob read failed at concurrency " + concurrency, failure.getCause());
                }
            }
        }
        latencies.sort(null);
        return new Run(latencies, (System.nanoTime() - start) / 1e6);
    }

    /** Markdown paths the commit added or deleted; a path-only move (R100) is matched by blob and never read. */
    private List<Candidate> candidates() throws Exception {
        var candidates = new ArrayList<Candidate>();
        for (String line : git("diff-tree", "-r", "-M100%", "--no-commit-id", "--raw", commit + "^", commit)
                .lines()
                .toList()) {
            String[] metaAndPath = line.split("\t");
            String[] meta = metaAndPath[0].split(" ");
            String status = meta[4];
            String path = metaAndPath[metaAndPath.length - 1];
            if (!path.toLowerCase().endsWith(".md")) continue;
            if (status.equals("A")) candidates.add(new Candidate(path, meta[3]));
            if (status.equals("D")) candidates.add(new Candidate(path, meta[2]));
        }
        return candidates;
    }

    private String git(String... arguments) throws Exception {
        var command = new ArrayList<>(List.of("git", "-c", "core.quotePath=false", "-C", corpus.toString()));
        command.addAll(List.of(arguments));
        var process = new ProcessBuilder(command).start();
        byte[] output = process.getInputStream().readAllBytes();
        if (process.waitFor() != 0)
            throw new IllegalStateException("git " + String.join(" ", arguments) + " failed in " + corpus);
        return new String(output, StandardCharsets.UTF_8);
    }
}
