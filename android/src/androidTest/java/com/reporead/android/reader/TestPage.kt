package com.reporead.android.reader

/**
 * A note's page in the server's shell (MarkdownRenderer.page), with [body] as its blocks, for tests without a server.
 */
internal fun testPage(sha: String, body: String) =
    """<!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
    <link rel="stylesheet" href="/assets/reader.css"><script defer src="/assets/reader.js"></script></head>
    <body data-source-blob-sha="$sha" data-max-diagram-chars="20000" data-max-edges="200">
    <p id="render-status"></p><main id="note">$body</main></body></html>"""
