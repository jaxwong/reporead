package com.reporead.android.study

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reporead.android.database.RepoReadDao
import com.reporead.android.ui.counted
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

/** What review holds now, from this phone's cards and grades; null while they are read. */
@Composable
internal fun rememberReviewOutlook(dao: RepoReadDao): ReviewOutlook? {
    val cards = dao.notebook().collectAsStateWithLifecycle(null).value
    val reviewLog = dao.reviews().collectAsStateWithLifecycle(null).value
    val today = rememberStartOfToday()
    return remember(cards, reviewLog, today) {
        if (cards == null || reviewLog == null) null
        else reviewOutlook(cards, reviewLog, System.currentTimeMillis(), today.zone)
    }
}

/**
 * The start of the phone's local day, in its time zone. Cards fall due at the start of a local day, so apart from cards
 * and grades only this changes an outlook: the date, and the zone that decides which day each grade fell on. It updates
 * at each local midnight while on screen, and whenever the screen starts, because the midnight timer does not run while
 * the phone sleeps and a zone change has no timer at all.
 */
@Composable
private fun rememberStartOfToday(): ZonedDateTime {
    var today by remember { mutableStateOf(startOfToday()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) today = startOfToday()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            val now = ZonedDateTime.now()
            delay(Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(now.zone)).toMillis())
            today = startOfToday()
        }
    }
    return today
}

private fun startOfToday(): ZonedDateTime =
    ZoneId.systemDefault().let { zone -> LocalDate.now(zone).atStartOfDay(zone) }

/**
 * One line on what review holds: the due count, then what falls due next — tomorrow or on the next day with cards — and
 * in the 7 days after the outlook's day. [sessionLimit] is the server's session size.
 */
internal fun outlookLine(outlook: ReviewOutlook, sessionLimit: Int?): String {
    val next =
        outlook.next
            ?: return if (outlook.due > 0) dueParts(outlook, sessionLimit).joinToString(" · ")
            else "No cards yet: grade a question below."
    val upcoming =
        if (next == outlook.today.plusDays(1)) "${outlook.nextCount} tomorrow"
        else
            "next on ${next.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))}, ${counted(outlook.nextCount, "card")}"
    return (dueParts(outlook, sessionLimit) +
            upcoming +
            listOfNotNull(outlook.week.takeIf { it > outlook.nextCount }?.let { "$it in the next 7 days" }))
        .joinToString(" · ")
}

private fun dueParts(outlook: ReviewOutlook, sessionLimit: Int?): List<String> =
    if (outlook.due == 0) listOf("Nothing due")
    else
        listOfNotNull(
            "${outlook.due} due",
            sessionLimit?.takeIf { outlook.due > it }?.let { "$it per session" },
            outlook.blocked.takeIf { it > 0 }?.let { "$it to save first" },
        )
