package com.reporead.document;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Renders every Markdown note of a real repository and writes, per note, its canonical blocks ({@code blocks/<path>.txt})
 * and its reader page ({@code pages/<path>.html}). Opt-in and read-only: runs only when REPOREAD_MEASURE_CORPUS names a
 * local Git clone; output goes to REPOREAD_MEASURE_OUT (default build/rendered-corpus). The output contains private
 * notes and is never committed.
 *
 * <p>A rendering change must leave canonical block text identical for an unchanged blob (ADR-05): run this before and
 * after the change into two directories and {@code diff -r} their {@code blocks}. The pages are for checking the reader
 * in a browser.
 */
@EnabledIfEnvironmentVariable(named = "REPOREAD_MEASURE_CORPUS", matches = ".+")
class RenderedCorpus {
    private final Path corpus = Path.of(System.getenv("REPOREAD_MEASURE_CORPUS"));
    private final Path out = Path.of(Optional.ofNullable(System.getenv("REPOREAD_MEASURE_OUT")).orElse("build/rendered-corpus"));

    @Test void render() throws Exception {
        int rendered = 0;
        var rejected = new ArrayList<String>();
        for (String path : git("ls-files", "-z", "*.md").split("\0")) {
            if (path.isEmpty()) continue;
            String markdown = git("show", "HEAD:" + path);
            var text = new StringBuilder();
            try {
                var note = MarkdownRenderer.render(markdown, MarkdownRenderer.blobSha(markdown.getBytes(StandardCharsets.UTF_8)), path);
                for (var block : note.blocks()) {
                    text.append(block.id()).append(' ').append(block.headingPath()).append('\n').append(block.text()).append("\n␞\n");
                }
                write(out.resolve("pages").resolve(path + ".html"), note.html());
                rendered++;
            } catch (MarkdownRenderer.ContentRejected rejection) {
                text.append("REJECTED ").append(rejection.getMessage()).append('\n');
                rejected.add(path + ": " + rejection.getMessage());
            }
            write(out.resolve("blocks").resolve(path + ".txt"), text.toString());
        }
        write(out.resolve("summary.txt"), "rendered " + rendered + "\nrejected " + rejected.size() + "\n" + String.join("\n", rejected) + "\n");
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private String git(String... arguments) throws IOException, InterruptedException {
        var command = new ArrayList<>(List.of("git", "-c", "core.quotePath=false", "-C", corpus.toString()));
        command.addAll(List.of(arguments));
        var process = new ProcessBuilder(command).redirectErrorStream(false).start();
        byte[] output = process.getInputStream().readAllBytes();
        if (process.waitFor() != 0) throw new IllegalStateException("git " + String.join(" ", arguments) + " failed in " + corpus);
        return new String(output, StandardCharsets.UTF_8);
    }
}
