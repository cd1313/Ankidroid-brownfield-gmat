// SPDX-License-Identifier: GPL-3.0-or-later
package com.ichi2.anki

import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import com.ichi2.anki.CollectionManager.withCol

/**
 * GMAT readiness dashboard.
 *
 * Shows the three GMAT scores per section, each separately (never blended), from
 * the shared engine:
 *  - Memory: FSRS retrievability of term flashcards (`get_topic_mastery`).
 *  - Performance: IRT ability θ / accuracy on new MCQs (`estimate_readiness`).
 *  - Readiness: projected section score (60–90) with a range + pacing.
 * Honest by design: a section below its give-up threshold shows "Not enough data
 * yet" instead of a number. Item difficulty is assumed (not calibrated) and the
 * readiness score is an approximate placeholder, not validated against real exams.
 */
class GmatDashboardActivity : AnkiActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (showedActivityFailedScreen(savedInstanceState)) {
            return
        }
        val textView =
            TextView(this).apply {
                setPadding(48, 48, 48, 48)
                textSize = 15f
            }
        setContentView(ScrollView(this).apply { addView(textView) })
        title = "GMAT Readiness"
        textView.text = "Loading…"

        launchCatchingTask {
            val text =
                withCol {
                    val memory =
                        backend.getTopicMastery(
                            search = "deck:\"GMAT::Terms\"",
                            tagPrefix = "GMAT",
                            rThreshold = 0.8f,
                            timeBudgetSecs = 20,
                            minReviews = 10,
                            minCards = 5,
                        )
                    val readiness =
                        backend.estimateReadiness(
                            search = "deck:\"GMAT::Practice\" note:\"GMAT MCQ\"",
                            tagPrefix = "GMAT",
                            timeBudgetSecs = 120,
                            sectionMinutes = 45,
                            minResponses = 20,
                            minCoverage = 0.0f,
                            maxSe = 0.7f,
                        )
                    render(memory, readiness)
                }
            textView.text = text
        }
    }

    private fun render(
        memory: List<anki.gmat.TopicMastery>,
        readiness: List<anki.gmat.SectionReadiness>,
    ): String {
        val sb = StringBuilder()

        sb.append("■ MEMORY — term recall (MCQ excluded)\n")
        if (memory.isEmpty()) {
            sb.append("  No GMAT term cards found.\n")
        } else {
            for (r in memory.sortedBy { it.topic }) {
                sb.append("  ${r.topic}\n")
                if (r.hasScore) {
                    val ps = Math.round(r.practicedScore * 100)
                    val pl = Math.round(r.practicedLow * 100)
                    val ph = Math.round(r.practicedHigh * 100)
                    val cs = Math.round(r.categoryScore * 100)
                    sb.append("    Practiced (studied): $ps% ($pl–$ph%)\n")
                    sb.append("    Category (whole section): $cs%\n")
                    sb.append("    ${r.reviewedCards}/${r.totalCards} reviewed · ${r.masteredCards} mastered\n")
                } else {
                    sb.append("    Not enough data yet (${r.reviewedCards}/${r.totalCards} reviewed)\n")
                }
            }
        }

        val sections = readiness.sortedBy { it.section }

        sb.append("\n■ PERFORMANCE — new MCQs (IRT ability)\n")
        if (sections.isEmpty()) {
            sb.append("  No practice attempts yet.\n")
        } else {
            for (s in sections) {
                sb.append("  ${s.section}\n")
                if (s.hasScore) {
                    val acc = Math.round(s.pctCorrect * 100)
                    sb.append("    $acc% accuracy · ability θ ${String.format("%+.2f", s.theta)} · ${s.responses} responses\n")
                } else {
                    sb.append("    Not enough data yet (${s.responses} responses; need ≥20)\n")
                }
            }
        }

        sb.append("\n■ READINESS — projected section score\n")
        if (sections.isEmpty()) {
            sb.append("  No practice attempts yet.\n")
        } else {
            for (s in sections) {
                sb.append("  ${s.section}\n")
                if (s.hasScore) {
                    val score = Math.round(s.score)
                    val lo = Math.round(s.scoreLow)
                    val hi = Math.round(s.scoreHigh)
                    val inBudget = Math.round(s.withinBudgetRate * 100)
                    val projected = Math.round(s.projectedSectionMinutes)
                    sb.append("    $score  (range $lo–$hi) · ${s.confidence} confidence\n")
                    sb.append("    pacing: $inBudget% in budget · ~$projected/45 min\n")
                } else {
                    sb.append("    Not enough data yet\n")
                }
            }
        }

        sb.append(
            "\nMemory: Practiced = studied cards (shown with a 10th–90th percentile range); " +
                "Category = whole section as a single number (unreviewed = 0). Performance = IRT " +
                "ability from timed MCQs (item difficulty assumed, not calibrated). Readiness " +
                "θ→score is an approximate placeholder, not validated against real exam outcomes.",
        )
        return sb.toString()
    }
}
