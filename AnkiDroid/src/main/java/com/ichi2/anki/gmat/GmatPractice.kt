// SPDX-License-Identifier: GPL-3.0-or-later
package com.ichi2.anki.gmat

import com.ichi2.anki.libanki.Collection

/**
 * FSRS-independent daily quota for GMAT MCQ practice.
 *
 * MCQ practice is a drill, not spaced repetition, so it's kept out of the normal
 * "due today" counts. Instead a fixed number of practice questions are "due" each
 * day. "Done today" is derived directly from the **review log** — distinct GMAT MCQ
 * cards answered since the day's rollover — rather than a hand-incremented counter.
 * The revlog is the synced source of truth, so the count is consistent across
 * desktop/mobile, self-correcting, and never spuriously resets.
 *
 * The daily target lives in the (synced) collection config under the same key the
 * desktop uses (`qt/aqt/gmat.py`).
 */
object GmatPractice {
    const val TARGET_KEY = "gmat_practice_daily_target"
    private const val DEFAULT_TARGET = 20
    private const val MCQ_NOTETYPE = "GMAT MCQ"

    fun dailyTarget(col: Collection): Int = col.config.get<Int>(TARGET_KEY) ?: DEFAULT_TARGET

    /** Distinct GMAT MCQ cards answered since today's rollover (from the revlog). */
    fun doneToday(col: Collection): Int {
        val mid = col.notetypes.byName(MCQ_NOTETYPE)?.id ?: return 0
        val dayStartMs = (col.sched.dayCutoff - 86400) * 1000
        return col.db
            .queryLongScalar(
                "select count(distinct r.cid) from revlog r " +
                    "join cards c on r.cid = c.id " +
                    "join notes n on c.nid = n.id " +
                    "where n.mid = ? and r.id >= ?",
                mid,
                dayStartMs,
            ).toInt()
    }

    /** Remaining practice questions in today's quota (never negative). */
    fun dueToday(col: Collection): Int = (dailyTarget(col) - doneToday(col)).coerceAtLeast(0)
}
