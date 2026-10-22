package com.reporead.sync;

import com.reporead.Hashes;
import com.reporead.document.MarkdownRenderer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Measures how alike a moved-and-edited note is to its old version, against how alike unrelated notes are; it is how
 * the content-move threshold was chosen. Opt-in and read-only like AnchorMeasurement: REPOREAD_MEASURE_CORPUS names a
 * local Git clone; aggregate results go to REPOREAD_MEASURE_OUT. Never commit the corpus or the output's samples.
 */
@EnabledIfEnvironmentVariable(named = "REPOREAD_MEASURE_CORPUS", matches = ".+")
class MoveMeasurement {
    /**
     * Git's rename score floor for real moves. Git's default (50%) would hide exactly the heavily edited moves the
     * threshold must not miss, so pairs between this and 50% are listed by path for checking by hand.
     */
    private static final int GIT_RENAME_FLOOR = 30;

    private final Path corpus = Path.of(System.getenv("REPOREAD_MEASURE_CORPUS"));
    private final Path out =
            Path.of(Optional.ofNullable(System.getenv("REPOREAD_MEASURE_OUT")).orElse("build/move-measurement.txt"));

    /** Shingles are computed once per note: the hardest-negative search compares every pair. */
    record Note(String path, int bytes, long renderNanos, List<MarkdownRenderer.Block> blocks, Set<String> shingles) {}

    record Rename(Note before, Note after, int gitScore) {}

    @Test
    void measure() throws Exception {
        var head = new ArrayList<Note>();
        for (String path : git("ls-files", "*.md").lines().toList())
            render(path, git("show", "HEAD:" + path)).ifPresent(head::add);
        // Real moves with edits: git's renames below 100% similarity, old and new version of each.
        var renames = new ArrayList<Rename>();
        for (String commit : git("log", "--format=%H").lines().toList()) {
            String[] ids =
                    git("rev-list", "--parents", "-n", "1", commit).strip().split(" ");
            if (ids.length != 2) continue;
            for (String change : git(
                            "diff-tree",
                            "-r",
                            "-M" + GIT_RENAME_FLOOR + "%",
                            "--no-commit-id",
                            "--name-status",
                            ids[1],
                            ids[0])
                    .lines()
                    .toList()) {
                String[] fields = change.split("\t");
                if (!fields[0].startsWith("R")
                        || fields[0].equals("R100")
                        || !fields[2].toLowerCase().endsWith(".md")) continue;
                var before = render(fields[1], git("show", ids[1] + ":" + fields[1]));
                var after = render(fields[2], git("show", ids[0] + ":" + fields[2]));
                if (before.isPresent() && after.isPresent())
                    renames.add(new Rename(before.get(), after.get(), Integer.parseInt(fields[0].substring(1))));
            }
        }
        var report = new StringBuilder("Real renames with edits: " + renames.size() + " (git score >= "
                + GIT_RENAME_FLOOR + "%); notes at HEAD: " + head.size() + "\n");
        reportRenderTimes(head, report);
        measure("block Dice", (a, b) -> blockSimilarity(a.blocks(), b.blocks()), head, renames, report);
        measure(
                "5-word shingle Jaccard",
                (a, b) -> ContentMoves.similarity(a.shingles(), b.shingles()),
                head,
                renames,
                report);
        measure("5-word shingle containment", (a, b) -> containment(a.shingles(), b.shingles()), head, renames, report);
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.writeString(out, report);
        System.out.println(report);
    }

    private static void reportRenderTimes(List<Note> head, StringBuilder report) {
        var byTime = head.stream()
                .sorted((a, b) -> Long.compare(a.renderNanos(), b.renderNanos()))
                .toList();
        var slowest = byTime.getLast();
        var largest = head.stream()
                .max((a, b) -> Integer.compare(a.bytes(), b.bytes()))
                .orElseThrow();
        report.append(String.format(
                "Render time at HEAD: median %.2f ms; 99th pct %.2f ms; max %.2f ms (%d bytes); largest note %d bytes"
                        + " took %.2f ms%n",
                byTime.get(byTime.size() / 2).renderNanos() / 1e6,
                byTime.get((int) (byTime.size() * 0.99)).renderNanos() / 1e6,
                slowest.renderNanos() / 1e6,
                slowest.bytes(),
                largest.bytes(),
                largest.renderNanos() / 1e6));
    }

    private static void measure(
            String name,
            BiFunction<Note, Note, Double> similarity,
            List<Note> head,
            List<Rename> renames,
            StringBuilder report) {
        var positives = renames.stream()
                .map(rename -> similarity.apply(rename.before(), rename.after()))
                .sorted()
                .toList();
        // The hardest negative for each note: its most similar other note.
        var negatives = new ArrayList<Double>();
        String worstPair = "";
        double worst = -1;
        for (var note : head) {
            double best = 0;
            String bestPath = "";
            for (var other : head) {
                if (other == note) continue;
                double value = similarity.apply(note, other);
                if (value > best) {
                    best = value;
                    bestPath = other.path();
                }
            }
            negatives.add(best);
            if (best > worst) {
                worst = best;
                worstPair = note.path() + " ~ " + bestPath;
            }
        }
        negatives.sort(null);
        report.append(String.format(
                "%n%s%n  real renames with edits (sorted): %s%n",
                name,
                positives.stream().map(value -> String.format("%.2f", value)).toList()));
        report.append("  renames git scored below 50% (check by hand; similarity, git score, both shingle counts):\n");
        for (var rename : renames) {
            if (rename.gitScore() >= 50) continue;
            report.append(String.format(
                    "    %.2f git %d%% shingles %d/%d  %s -> %s%n",
                    similarity.apply(rename.before(), rename.after()),
                    rename.gitScore(),
                    rename.before().shingles().size(),
                    rename.after().shingles().size(),
                    rename.before().path(),
                    rename.after().path()));
        }
        report.append(String.format(
                "  most similar other note: max %.3f (%s); 99th pct %.3f; 95th pct %.3f; median %.3f%n",
                worst,
                worstPair,
                negatives.get((int) (negatives.size() * 0.99)),
                negatives.get((int) (negatives.size() * 0.95)),
                negatives.get(negatives.size() / 2)));
        report.append("  notes whose most similar other note is above 0.5: ")
                .append(negatives.stream().filter(value -> value > 0.5).count())
                .append('\n');
        if (name.startsWith("5-word")) {
            report.append("  shingles in real renames (old, new; smallest first): ")
                    .append(renames.stream()
                            .map(rename -> Math.min(
                                    rename.before().shingles().size(),
                                    rename.after().shingles().size()))
                            .sorted()
                            .toList())
                    .append('\n');
            for (int minimum : List.of(10, 20, 30, 50)) {
                var eligible = head.stream()
                        .filter(note -> note.shingles().size() >= minimum)
                        .toList();
                double max = 0;
                String pair = "";
                int identical = 0;
                for (int i = 0; i < eligible.size(); i++) {
                    for (int j = i + 1; j < eligible.size(); j++) {
                        double value = similarity.apply(eligible.get(i), eligible.get(j));
                        if (value == 1.0) {
                            identical++;
                            continue;
                        }
                        if (value > max) {
                            max = value;
                            pair = eligible.get(i).path() + " ~ "
                                    + eligible.get(j).path();
                        }
                    }
                }
                report.append(String.format(
                        "  notes with >= %d shingles: %d; identical-content pairs %d; most similar non-identical pair %.3f (%s)%n",
                        minimum, eligible.size(), identical, max, pair));
            }
        }
    }

    /**
     * The alternative to Jaccard for a note that grew or shrank as it moved: shared shingles over the smaller set, so a
     * note whose old text survives whole scores 1 however much was added.
     */
    private static double containment(Set<String> left, Set<String> right) {
        if (left.isEmpty() || right.isEmpty()) return left.isEmpty() && right.isEmpty() ? 1.0 : 0.0;
        var smaller = left.size() <= right.size() ? left : right;
        var larger = smaller == left ? right : left;
        int common = 0;
        for (String shingle : smaller) if (larger.contains(shingle)) common++;
        return (double) common / smaller.size();
    }

    /** The alternative compared and not chosen: Dice over the multisets of canonical block texts. */
    private static double blockSimilarity(List<MarkdownRenderer.Block> a, List<MarkdownRenderer.Block> b) {
        if (a.isEmpty() && b.isEmpty()) return 1.0;
        var counts = new java.util.HashMap<String, Integer>();
        for (var block : a) counts.merge(block.text(), 1, Integer::sum);
        int common = 0;
        for (var block : b) {
            Integer left = counts.get(block.text());
            if (left != null && left > 0) {
                common++;
                counts.put(block.text(), left - 1);
            }
        }
        return 2.0 * common / (a.size() + b.size());
    }

    private Optional<Note> render(String path, String markdown) {
        byte[] bytes = markdown.getBytes(StandardCharsets.UTF_8);
        String sha = Hashes.gitBlobSha(bytes);
        try {
            long start = System.nanoTime();
            var blocks = MarkdownRenderer.render(markdown, sha, path).blocks();
            long renderNanos = System.nanoTime() - start;
            return Optional.of(new Note(path, bytes.length, renderNanos, blocks, ContentMoves.shingles(blocks)));
        } catch (MarkdownRenderer.ContentRejected rejected) {
            return Optional.empty();
        }
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
