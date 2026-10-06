package com.reporead.document;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class RenderNote {
    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--help")) {
            System.out.println("RenderNote <UTF-8-markdown-file> <git-blob-sha> <source-label> <output-html-file>");
            System.out.println("RenderNote --fixture <UTF-8-markdown-file> <output-html-file> (explicit synthetic test only)");
            return;
        }
        boolean fixture = args.length == 3 && args[0].equals("--fixture");
        if (!fixture && args.length != 4) throw new IllegalArgumentException("RenderNote expects 4 arguments or --fixture; use --help");
        var input = Path.of(args[fixture ? 1 : 0]);
        byte[] bytes;
        try (var stream = Files.newInputStream(input)) {
            bytes = stream.readNBytes(MarkdownRenderer.MAX_NOTE_BYTES + 1);
        }
        if (bytes.length > MarkdownRenderer.MAX_NOTE_BYTES) throw new MarkdownRenderer.ContentRejected("RenderNote input exceeds 1 MiB");
        String markdown = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        String sha = fixture ? MarkdownRenderer.blobSha(bytes) : args[1];
        String label = fixture ? "Synthetic Markdown fixture — not GitHub integration" : args[2];
        var rendered = MarkdownRenderer.render(markdown, sha, label);
        Files.writeString(Path.of(args[fixture ? 2 : 3]), rendered.html(), StandardCharsets.UTF_8);
        System.out.printf("RENDERED blocks=%d diagrams=%d sourceBlobSha=%s%n", rendered.blocks().size(), rendered.diagramCount(), sha);
    }
}
