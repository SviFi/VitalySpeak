package com.svifi.vitalyspeak

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Puts dictated text into the text field the user is typing in.
 *
 * 1. Find the field with input focus (active window first, then other app windows).
 * 2. If it accepts "set text": insert at the cursor (replacing a selection), adding a space
 *    when the text would otherwise stick to the previous word, and move the cursor after it.
 * 3. Otherwise paste it via the clipboard.
 * 4. If neither works, leave it on the clipboard so the user can paste manually.
 */
class TextInserter(private val service: AccessibilityService) {

    enum class Result { INSERTED, PASTED, CLIPBOARD }

    fun insert(text: String): Result {
        val field = focusedField()
        if (field != null) {
            if (setText(field, text)) return Result.INSERTED
            copy(text)
            if (field.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return Result.PASTED
        } else copy(text)
        return Result.CLIPBOARD
    }

    private fun copy(text: String) {
        (service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("VitalySpeak", text))
    }

    private fun focusedField(): AccessibilityNodeInfo? {
        val roots = ArrayList<AccessibilityNodeInfo>()
        service.rootInActiveWindow?.let { roots += it }
        try {
            service.windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .sortedByDescending { it.isFocused }
                .mapNotNullTo(roots) { it.root }
        } catch (_: Exception) {}
        for (root in roots) {
            if (root.packageName == service.packageName) continue
            val f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: continue
            if (f.isEditable || f.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT || it.id == AccessibilityNodeInfo.ACTION_PASTE }) return f
        }
        return null
    }

    private fun setText(field: AccessibilityNodeInfo, insert: String): Boolean {
        if (field.actionList.none { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }) return false
        // Placeholder text ("Message…") is reported as text by some apps: treat it as empty.
        val existing = if (field.isShowingHintText) "" else field.text?.toString().orEmpty()
        val a = field.textSelectionStart.takeIf { it in 0..existing.length } ?: existing.length
        val b = field.textSelectionEnd.takeIf { it in 0..existing.length } ?: a
        val from = minOf(a, b)
        val to = maxOf(a, b)
        val piece = Spacing.join(existing.substring(0, from), insert, existing.substring(to))
        val updated = existing.substring(0, from) + piece + existing.substring(to)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updated) }
        if (!field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false
        val caret = from + piece.length
        field.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, caret)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, caret)
        })
        return true
    }
}

/** Spacing rules when inserting dictated text into existing text (unit-tested). */
object Spacing {
    /** Returns [insert] with a leading/trailing space added where needed. */
    fun join(before: String, insert: String, after: String): String {
        var s = insert
        val prev = before.lastOrNull()
        val next = after.firstOrNull()
        if (prev != null && !prev.isWhitespace() && s.firstOrNull()?.let { it.isLetterOrDigit() } == true) s = " $s"
        if (next != null && !next.isWhitespace() && next !in ".,!?;:)]}" && s.lastOrNull()?.isWhitespace() == false) s = "$s "
        return s
    }
}
