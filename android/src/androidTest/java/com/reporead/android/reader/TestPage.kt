package com.reporead.android.reader

import android.content.Context
import com.reporead.android.BuildConfig
import com.reporead.android.database.SavedPageEntity
import com.reporead.android.network.Api
import com.reporead.android.sync.SavedPageImages
import java.io.File

/**
 * A note's page in the server's shell (MarkdownRenderer.page), with [body] as its blocks, for tests without a server.
 */
internal fun testPage(sha: String, body: String) =
    """<!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
    <link rel="stylesheet" href="/assets/reader.css"><script defer src="/assets/reader.js"></script></head>
    <body data-source-blob-sha="$sha" data-max-diagram-chars="20000" data-max-edges="200">
    <p id="render-status"></p><main id="note">$body</main></body></html>"""

/** [testPage] as a test-only saved page for an [IsolatedPage]; it is never written to a database. */
internal fun testSavedPage(body: String): SavedPageEntity {
    val sha = "a".repeat(40)
    return SavedPageEntity(
        1,
        sha,
        "test-only/page.md",
        "Test-only page",
        testPage(sha, body),
        0,
        renderFormat = BuildConfig.RENDER_FORMAT,
    )
}

/** Images for a test page, which has none: no server is reachable, and asking for one fails the test. */
internal fun noImages(context: Context) =
    SavedPageImages(
        Api("http://127.0.0.1:1", { error("A test page must not call the network") }) {},
        File(context.cacheDir, "isolated-page-test"),
    )
