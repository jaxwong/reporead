package com.reporead.android.reader

import android.content.Context
import android.graphics.Rect
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.WebView

private const val HIGHLIGHT = 0x5245_0001
private const val ADD_NOTE = 0x5245_0002

/**
 * Adds Highlight and Add note to the WebView's native text-selection menu. The selection itself is read by native code
 * through evaluateJavascript; the page gains no way to call native code. [onAction] receives whether a note was
 * requested and a callback that closes the menu once the selection has been read.
 */
internal class ReaderWebView(context: Context, private val onAction: (withNote: Boolean, finish: () -> Unit) -> Unit) : WebView(context) {
    override fun startActionMode(callback: ActionMode.Callback, type: Int): ActionMode? =
        super.startActionMode(SelectionActions(callback), type)

    private inner class SelectionActions(private val wrapped: ActionMode.Callback) : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            val created = wrapped.onCreateActionMode(mode, menu)
            menu.add(Menu.NONE, HIGHLIGHT, 0, "Highlight")
            menu.add(Menu.NONE, ADD_NOTE, 1, "Add note")
            return created
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = wrapped.onPrepareActionMode(mode, menu)

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean = when (item.itemId) {
            HIGHLIGHT, ADD_NOTE -> {
                onAction(item.itemId == ADD_NOTE) { mode.finish() }
                true
            }
            else -> wrapped.onActionItemClicked(mode, item)
        }

        override fun onDestroyActionMode(mode: ActionMode) = wrapped.onDestroyActionMode(mode)

        override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
            if (wrapped is ActionMode.Callback2) wrapped.onGetContentRect(mode, view, outRect) else super.onGetContentRect(mode, view, outRect)
        }
    }
}
