package com.reporead.android

import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.WebView
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue

// What device tests read and tap on screen, through accessibility as a screen reader would: a node's text, or an icon's
// description. Waits give up after 10 s and name what was visible.

internal fun root(): AccessibilityNodeInfo? =
    InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow

internal fun findNode(
    node: AccessibilityNodeInfo?,
    matches: (AccessibilityNodeInfo) -> Boolean,
): AccessibilityNodeInfo? {
    if (node == null) return null
    if (matches(node)) return node
    for (i in 0 until node.childCount) findNode(node.getChild(i), matches)?.let {
        return it
    }
    return null
}

internal fun findText(node: AccessibilityNodeInfo?, matches: (String) -> Boolean) =
    findNode(node) { (it.text ?: it.contentDescription)?.toString()?.let(matches) == true }

internal fun findText(node: AccessibilityNodeInfo?, text: String) = findText(node) { it == text }

private fun awaitNode(description: String, matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo =
    runBlocking {
        try {
            withTimeout(10_000) {
                while (true) {
                    findNode(root(), matches)?.let {
                        return@withTimeout it
                    }
                    delay(50)
                }
                error("Unreachable")
            }
        } catch (error: TimeoutCancellationException) {
            fun texts(node: AccessibilityNodeInfo?): List<String> =
                if (node == null) emptyList()
                else
                    listOfNotNull(node.text?.toString()) +
                        (0 until node.childCount).flatMap { texts(node.getChild(it)) }
            throw AssertionError("Timed out waiting for $description; visible texts=${texts(root())}", error)
        }
    }

internal fun awaitMatch(description: String, matches: (String) -> Boolean) =
    awaitNode(description) { (it.text ?: it.contentDescription)?.toString()?.let(matches) == true }

internal fun awaitText(text: String) = awaitMatch("'$text'") { it == text }

internal fun awaitTextStarting(prefix: String) = awaitMatch("text starting '$prefix'") { it.startsWith(prefix) }

/** The control showing [text]: a button's label can be a node of its own inside the button. */
internal fun control(text: String): AccessibilityNodeInfo {
    var node = awaitText(text)
    while (!node.isClickable) node = checkNotNull(node.parent) { "No clickable parent for $text" }
    return node
}

internal fun click(text: String) {
    assertTrue("Could not click $text", control(text).performAction(AccessibilityNodeInfo.ACTION_CLICK))
    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
}

/** Types [text] into the text field on screen, as the keyboard would. */
internal fun type(text: String) {
    val target = awaitNode("a text field") { it.isEditable }
    val arguments =
        Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
    assertTrue(
        "Could not type into the text field",
        target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments),
    )
    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
}

internal fun findWebView(view: View): WebView? {
    if (view is WebView) return view
    if (view is ViewGroup)
        for (index in 0 until view.childCount) findWebView(view.getChildAt(index))?.let {
            return it
        }
    return null
}

/** A real pointer tap, without an idle wait between events; callers may dispatch a same-frame double tap. */
internal fun tap(decor: View, bounds: Rect) {
    val location = IntArray(2).also(decor::getLocationOnScreen)
    val now = SystemClock.uptimeMillis()
    for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
        val event =
            MotionEvent.obtain(
                now,
                now,
                action,
                bounds.exactCenterX() - location[0],
                bounds.exactCenterY() - location[1],
                0,
            )
        try {
            assertTrue("The pointer event is dispatched", decor.dispatchTouchEvent(event))
        } finally {
            event.recycle()
        }
    }
}
