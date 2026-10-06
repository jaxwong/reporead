package com.reporead.android.reader

import android.content.Context
import android.graphics.Rect
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.WebView

/** What the reader can do with a text selection; [REATTACH] is offered only while a highlight is being reattached. */
internal enum class SelectionAction(val id: Int, val label: String) {
    HIGHLIGHT(0x5245_0001, "Highlight"),
    ADD_NOTE(0x5245_0002, "Add note"),
    REATTACH(0x5245_0003, "Reattach here"),
}

/**
 * Adds the reader's actions to the WebView's native text-selection menu. The selection itself is read by native code
 * through evaluateJavascript; the page gains no way to call native code. [onAction] receives the action and a callback
 * that closes the menu once the selection has been read.
 */
internal class ReaderWebView(
    context: Context,
    private val reattaching: () -> Boolean,
    private val onAction: (action: SelectionAction, finish: () -> Unit) -> Unit,
) : WebView(context) {
    override fun startActionMode(callback: ActionMode.Callback, type: Int): ActionMode? =
        super.startActionMode(SelectionActions(callback), type)

    private inner class SelectionActions(private val wrapped: ActionMode.Callback) : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            val created = wrapped.onCreateActionMode(mode, menu)
            val actions = if (reattaching()) listOf(SelectionAction.REATTACH) else listOf(SelectionAction.HIGHLIGHT, SelectionAction.ADD_NOTE)
            actions.forEachIndexed { order, action -> menu.add(Menu.NONE, action.id, order, action.label) }
            return created
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = wrapped.onPrepareActionMode(mode, menu)

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            val action = SelectionAction.entries.firstOrNull { it.id == item.itemId } ?: return wrapped.onActionItemClicked(mode, item)
            onAction(action) { mode.finish() }
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) = wrapped.onDestroyActionMode(mode)

        override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
            if (wrapped is ActionMode.Callback2) wrapped.onGetContentRect(mode, view, outRect) else super.onGetContentRect(mode, view, outRect)
        }
    }
}
