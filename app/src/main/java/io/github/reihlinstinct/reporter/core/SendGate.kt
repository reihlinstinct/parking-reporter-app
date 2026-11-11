package io.github.reihlinstinct.reporter.core

/**
 * The M-B send gate (issue #153): the draft SHOWN on the review screen is
 * what goes to the municipality - never a silently different copy. Send is
 * the single confirmation (owner instruction 2026-11-10, phonemsg
 * 01M73E6E2S6AZP46K75QS2S1WY): there is no separate approval latch, so the
 * gate is that the shown draft is complete. AppShell persists that exact
 * draft before the first network call, so a failed or dropped save never
 * sends stale text.
 */
object SendGate {
    fun readyToSend(shown: ReviewDraft?): Boolean = shown != null && shown.isReady()
}
