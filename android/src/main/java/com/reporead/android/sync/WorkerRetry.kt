// Owns: the retry ceiling and backoff shared by RepoRead background calls.

package com.reporead.android.sync

import com.reporead.android.logging.AppLog
import com.reporead.android.network.ApiException
import java.time.Duration

/**
 * Ten attempts have nine waits, doubling from 30 s: 30 s × (2⁹ − 1), about 4 hours 15 minutes of server or GitHub
 * failure. Time without a network does not count.
 */
internal val FIRST_BACKOFF: Duration = Duration.ofSeconds(30)
private const val MAX_ATTEMPTS = 10

/**
 * Whether WorkManager tries [operation] again after [failure]: only a retryable failure (unreachable, a 5xx, GitHub
 * unavailable), and only within [MAX_ATTEMPTS]. WorkManager counts every start as an attempt, also one after Android
 * stopped the run.
 */
internal fun shouldRetry(operation: String, failure: ApiException, attempt: Int): Boolean {
    val retryable = failure.unreachable || failure.status >= 500
    val willRetry = retryable && attempt + 1 < MAX_ATTEMPTS
    AppLog.warn("$operation failed; attempt=$attempt status=${failure.status} code=${failure.code} retry=$willRetry")
    return willRetry
}
