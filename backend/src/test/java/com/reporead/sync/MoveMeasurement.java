package com.reporead.sync;

import com.reporead.document.MarkdownRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * Measures how alike a moved-and-edited note is to its old version, against how alike unrelated notes are; it is how
 * the content-move threshold was chosen. Opt-in and read-only like AnchorMeasurement: REPOREAD_MEASURE_CORPUS names a
 * local Git clone; aggregate results go to REPOREAD_MEASURE_OUT. Never commit the corpus or the output's samples.
 */
@EnabledIfEnvironmentVariable(named = "REPOREAD_MEASURE_CORPUS", matches = ".+")
class MoveMeasurement {
    private final Path corpus = Path.of(System.getenv("REPOREAD_MEASURE_CORPUS"));
    private final Path out = Path.of(Optional.ofNullable(System.getenv("REPOREAD_MEASURE_OUT")).orElse("build/move-measurement.txt"));

    record Note(String path, List<MarkdownRenderer.Block> blocks) {}

    @Test void measure() throws Exception {
        var head = new ArrayList<Note>();
        for (String path : git("ls-files", "*.md").lines().toList()) render(path, git("show", "HEAD:" + path)).ifPresent(head::add);
        // Real moves with edits: git's renames below 100% similarity, old and new version of each.
        var renames = new ArrayList<Note[]>();
        for (String commit : git("log", "--format=%H").lines().toList()) {
            String[] ids = git("rev-list", "--parents", "-n", "1", commit).strip().split(" ");
            if (ids.length != 2) continue;
            for (String change : git("diff-tree", "-r", "-M", "--no-commit-id", "--name-status", ids[1], ids[0]).lines().toList()) {
                String[] fields = change.split("\t");
                if (!fields[0].startsWith("R") || fields[0].equals("R100") || !fields[2].toLowerCase().endsWith(".md")) continue;
                var before = render(fields[1], git("show", ids[1] + ":" + fields[1]));
                var after = render(fields[2], git("show", ids[0] + ":" + fields[2]));
                if (before.isPresent() && after.isPresent()) renames.add(new Note[] {before.get(), after.get()});
            }
        }
        var report = new StringBuilder("Real renames with edits: " + renames.size() + "; notes at HEAD: " + head.size() + "\n");
        measure("block Dice", MoveMeasurement::blockSimilarity, head, renames, report);
        measure("5-word shingle Jaccard", (a, b) -> ContentMoves.similarity(ContentMoves.shingles(a), ContentMoves.shingles(b)), head, renames, report);
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.writeString(out, report);
        System.out.println(report);
    }

    private static void measure(String name, BiFunction<List<MarkdownRenderer.Block>, List<MarkdownRenderer.Block>, Double> similarity,
                                List<Note> head, List<Note[]> renames, StringBuilder report) {
        var positives = renames.stream().map(pair -> similarity.apply(pair[0].blocks(), pair[1].blocks())).sorted().toList();
        // The hardest negative for each note: its most similar other note.
        var negatives = new ArrayList<Double>();
        String worstPair = "";
        double worst = -1;
        for (var note : head) {
            double best = 0;
            String bestPath = "";
            for (var other : head) {
                if (other == note) continue;
                double value = similarity.apply(note.blocks(), other.blocks());
                if (value > best) { best = value; bestPath = other.path(); }
            }
            negatives.add(best);
            if (best > worst) { worst = best; worstPair = note.path() + " ~ " + bestPath; }
        }
        negatives.sort(null);
        report.append(String.format("%n%s%n  real renames with edits (sorted): %s%n", name,
            positives.stream().map(value -> String.format("%.2f", value)).toList()));
        report.append(String.format("  most similar other note: max %.3f (%s); 99th pct %.3f; 95th pct %.3f; median %.3f%n", worst, worstPair,
            negatives.get((int) (negatives.size() * 0.99)), negatives.get((int) (negatives.size() * 0.95)), negatives.get(negatives.size() / 2)));
        report.append("  notes whose most similar other note is above 0.5: ")
            .append(negatives.stream().filter(value -> value > 0.5).count()).append('\n');
        if (name.startsWith("5-word")) {
            report.append("  shingles in real renames (old, new; smallest first): ").append(renames.stream()
                .map(pair -> Math.min(ContentMoves.shingles(pair[0].blocks()).size(), ContentMoves.shingles(pair[1].blocks()).size()))
                .sorted().toList()).append('\n');
            for (int minimum : List.of(10, 20, 30, 50)) {
                var eligible = head.stream().filter(note -> ContentMoves.shingles(note.blocks()).size() >= minimum).toList();
                double max = 0;
                String pair = "";
                int identical = 0;
                for (int i = 0; i < eligible.size(); i++) {
                    for (int j = i + 1; j < eligible.size(); j++) {
                        double value = similarity.apply(eligible.get(i).blocks(), eligible.get(j).blocks());
                        if (value == 1.0) { identical++; continue; }
                        if (value > max) { max = value; pair = eligible.get(i).path() + " ~ " + eligible.get(j).path(); }
                    }
                }
                report.append(String.format("  notes with >= %d shingles: %d; identical-content pairs %d; most similar non-identical pair %.3f (%s)%n",
                    minimum, eligible.size(), identical, max, pair));
            }
        }
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
        String sha = MarkdownRenderer.blobSha(markdown.getBytes(StandardCharsets.UTF_8));
        try {
            return Optional.of(new Note(path, MarkdownRenderer.render(markdown, sha, path).blocks()));
        } catch (MarkdownRenderer.ContentRejected rejected) {
            return Optional.empty();
        }
    }

    private String git(String... arguments) throws Exception {
        var command = new ArrayList<>(List.of("git", "-c", "core.quotePath=false", "-C", corpus.toString()));
        command.addAll(List.of(arguments));
        var process = new ProcessBuilder(command).start();
        byte[] output = process.getInputStream().readAllBytes();
        if (process.waitFor() != 0) throw new IllegalStateException("git " + String.join(" ", arguments) + " failed in " + corpus);
        return new String(output, StandardCharsets.UTF_8);
    }
}
