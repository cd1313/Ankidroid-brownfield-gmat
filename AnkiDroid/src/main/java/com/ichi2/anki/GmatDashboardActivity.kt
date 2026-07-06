// SPDX-License-Identifier: GPL-3.0-or-later
package com.ichi2.anki

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.view.setPadding
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.common.preferences.sharedPrefs
import com.ichi2.anki.gmat.CorrectPeerActivity
import com.ichi2.anki.gmat.GmatAi
import com.ichi2.anki.gmat.GmatPractice

/**
 * GMAT dashboard — the mobile counterpart of the desktop deck-browser hero.
 *
 * Shows a cat mascot, an AI-features master switch (+ OpenAI key entry), per-section
 * Memory% / Readiness cards from the shared engine (`get_topic_mastery` /
 * `estimate_readiness`), and a launcher for the "Correct the Peer" game.
 *
 * The three GMAT scores are always kept separate (never blended). Honest by design:
 * a section below its give-up threshold shows "Not enough data yet". Item difficulty
 * is assumed (not calibrated) and the readiness score is an approximate placeholder.
 */
class GmatDashboardActivity : AnkiActivity() {
    private lateinit var sectionsContainer: LinearLayout
    private lateinit var aiSwitch: MaterialSwitch
    private lateinit var keyHint: TextView
    private lateinit var practiceDue: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (showedActivityFailedScreen(savedInstanceState)) {
            return
        }
        val toolbar =
            MaterialToolbar(this).apply {
                setBackgroundColor(themeColor(androidx.appcompat.R.attr.colorPrimary))
                title = "GMAT"
                setTitleTextColor(Color.WHITE)
            }
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(
                    toolbar,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ),
                )
                addView(
                    buildContent(),
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
                )
            }
        setContentView(root)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        refresh()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun themeColor(attr: Int): Int {
        val tv = TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    override fun onResume() {
        super.onResume()
        aiSwitch.isChecked = GmatAi.aiToggleOn(this)
        updateKeyHint()
    }

    // ---- UI construction (programmatic to avoid layout-string lint) ---------

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun sectionHeader(text: String): TextView =
        TextView(this).apply {
            this.text = text
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(themeColor(androidx.appcompat.R.attr.colorPrimary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(4), dp(14), dp(4), dp(2))
        }

    private fun buildContent(): View {
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16))
            }

        // Header: mascot + title
        root.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    TextView(context).apply {
                        text = "🐱"
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f)
                        setPadding(0, 0, dp(12), 0)
                    },
                )
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(
                            TextView(context).apply {
                                text = "Welcome back"
                                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                                setTypeface(typeface, Typeface.BOLD)
                            },
                        )
                        addView(
                            TextView(context).apply {
                                text = "Your GMAT readiness at a glance"
                                alpha = 0.7f
                            },
                        )
                    },
                )
            },
        )

        // AI controls card
        root.addView(sectionHeader("AI"))
        root.addView(
            card().apply {
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(
                            LinearLayout(context).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = Gravity.CENTER_VERTICAL
                                addView(
                                    TextView(context).apply {
                                        text = "AI features"
                                        setTypeface(typeface, Typeface.BOLD)
                                        layoutParams =
                                            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                                    },
                                )
                                aiSwitch =
                                    MaterialSwitch(context).apply {
                                        isChecked = GmatAi.aiToggleOn(this@GmatDashboardActivity)
                                        setOnCheckedChangeListener { _, checked ->
                                            sharedPrefs().edit().putBoolean(GmatAi.PREF_AI_ENABLED, checked).apply()
                                            updateKeyHint()
                                        }
                                    }
                                addView(aiSwitch)
                            },
                        )
                        addView(
                            MaterialButton(
                                context,
                                null,
                                com.google.android.material.R.attr.materialButtonOutlinedStyle,
                            ).apply {
                                text = "Set OpenAI key"
                                setOnClickListener { promptForApiKey() }
                            },
                        )
                        keyHint =
                            TextView(context).apply {
                                text = "Add your OpenAI API key to turn on AI grading & peer help."
                                alpha = 0.7f
                                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                            }
                        addView(keyHint)
                    },
                )
            },
        )

        // Per-section cards get added here after the RPC returns.
        root.addView(sectionHeader("Progress by section"))
        sectionsContainer =
            LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(sectionsContainer)

        // Caption clarifying what the numbers mean (mirrors the desktop caption).
        root.addView(
            TextView(this).apply {
                text =
                    "Memory = how well you recall this section's terms (not test " +
                    "performance). Performance = IRT ability from timed MCQs " +
                    "(item difficulty assumed, not calibrated). Readiness is an " +
                    "approximate projected score, not validated against real exams."
                alpha = 0.7f
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(dp(4), dp(4), dp(4), dp(12))
            },
        )

        // Correct the Peer launcher
        root.addView(sectionHeader("Practice"))
        practiceDue =
            TextView(this).apply {
                text = "MCQ practice due today: …"
                setPadding(dp(4), dp(2), dp(4), dp(8))
            }
        root.addView(practiceDue)
        root.addView(
            MaterialButton(this).apply {
                text = "Correct the Peer"
                setOnClickListener {
                    startActivity(Intent(this@GmatDashboardActivity, CorrectPeerActivity::class.java))
                }
                layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
            },
        )

        return ScrollView(this).apply { addView(root) }
    }

    /** A Material card with vertical padding, ready to receive one child. */
    private fun card(): MaterialCardView =
        MaterialCardView(this).apply {
            radius = dp(18).toFloat()
            cardElevation = dp(1).toFloat()
            layoutParams =
                LinearLayout
                    .LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { setMargins(0, dp(6), 0, dp(6)) }
            setContentPadding(dp(16), dp(14), dp(16), dp(14))
        }

    private fun promptForApiKey() {
        val input =
            EditText(this).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                setText(GmatAi.apiKey(this@GmatDashboardActivity) ?: "")
                hint = "sk-…"
            }
        MaterialAlertDialogBuilder(this)
            .setTitle("OpenAI API key")
            .setMessage("Stored on this device only. Used for AI term grading and peer help.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                sharedPrefs().edit().putString(GmatAi.PREF_API_KEY, input.text.toString().trim()).apply()
                updateKeyHint()
            }.setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateKeyHint() {
        val hasKey = GmatAi.apiKey(this) != null
        // Only nag about the key when the user has switched AI on but has no key.
        keyHint.isVisible = aiSwitch.isChecked && !hasKey
    }

    // ---- data --------------------------------------------------------------

    private fun refresh() {
        launchCatchingTask {
            val memory =
                withCol {
                    backend.getTopicMastery(
                        search = "deck:\"GMAT::Terms\"",
                        tagPrefix = "GMAT",
                        rThreshold = 0.8f,
                        timeBudgetSecs = 20,
                        minReviews = 10,
                        minCards = 5,
                    )
                }
            val readiness =
                withCol {
                    backend.estimateReadiness(
                        search = "deck:\"GMAT::Practice\" note:\"GMAT MCQ\"",
                        tagPrefix = "GMAT",
                        timeBudgetSecs = 120,
                        sectionMinutes = 45,
                        minResponses = 20,
                        minCoverage = 0.0f,
                        maxSe = 0.7f,
                    )
                }
            val practice = withCol { GmatPractice.dueToday(this) }
            practiceDue.text =
                if (practice > 0) {
                    "MCQ practice due today: $practice"
                } else {
                    "MCQ practice: done for today 🎉"
                }
            renderSectionCards(memory, readiness)
        }
    }

    private fun sectionShort(name: String): String = name.removePrefix("GMAT::").replace("::", " · ")

    /** A "Label   value" row: muted fixed-width label + value. */
    private fun metricRow(
        label: String,
        value: String,
    ): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(3), 0, dp(3))
            addView(
                TextView(context).apply {
                    text = label
                    setTextColor(themeColor(androidx.appcompat.R.attr.colorPrimary))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    layoutParams = LinearLayout.LayoutParams(dp(96), LinearLayout.LayoutParams.WRAP_CONTENT)
                },
            )
            addView(
                TextView(context).apply {
                    text = value
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                },
            )
        }

    private fun renderSectionCards(
        memory: List<anki.gmat.TopicMastery>,
        readiness: anki.gmat.ReadinessResponse,
    ) {
        sectionsContainer.removeAllViews()
        val memoryByTopic = memory.associateBy { it.topic }
        val readinessBySection = readiness.sectionsList.associateBy { it.section }
        val sections = (memoryByTopic.keys + readinessBySection.keys).toSortedSet()
        if (sections.isEmpty()) {
            sectionsContainer.addView(
                card().apply {
                    addView(TextView(context).apply { text = "No GMAT data yet." })
                },
            )
            return
        }
        for (name in sections) {
            val m = memoryByTopic[name]
            val r = readinessBySection[name]
            val memPct = if (m != null && m.hasScore) Math.round(m.categoryScore * 100) else null
            sectionsContainer.addView(
                card().apply {
                    addView(
                        LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL

                            // Title + memory progress bar
                            addView(
                                TextView(context).apply {
                                    text = sectionShort(name)
                                    setTypeface(typeface, Typeface.BOLD)
                                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                                },
                            )
                            addView(
                                ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                                    max = 100
                                    progress = memPct ?: 0
                                    layoutParams =
                                        LinearLayout
                                            .LayoutParams(
                                                LinearLayout.LayoutParams.MATCH_PARENT,
                                                dp(6),
                                            ).apply { setMargins(0, dp(6), 0, dp(8)) }
                                },
                            )

                            // Memory row — show BOTH the recall over cards you've
                            // actually studied AND the whole-section score.
                            val memoryValue =
                                if (m != null && m.hasScore) {
                                    val practiced = Math.round(m.practicedScore * 100)
                                    val lo = Math.round(m.practicedLow * 100)
                                    val hi = Math.round(m.practicedHigh * 100)
                                    "Studied cards: $practiced% ($lo–$hi%)\n" +
                                        "Whole section: $memPct% · ${m.reviewedCards}/${m.totalCards} reviewed"
                                } else {
                                    val reviewed = m?.reviewedCards ?: 0
                                    val total = m?.totalCards ?: 0
                                    "Not enough data yet ($reviewed/$total reviewed)"
                                }
                            addView(metricRow("Memory", memoryValue))

                            // Performance row
                            val performanceValue =
                                if (r != null && r.hasScore) {
                                    val acc = Math.round(r.pctCorrect * 100)
                                    "$acc% correct · θ ${String.format("%+.2f", r.theta)} · ${r.responses} MCQs"
                                } else {
                                    val responses = r?.responses ?: 0
                                    "Not enough data yet ($responses/20 MCQs)"
                                }
                            addView(metricRow("Performance", performanceValue))

                            // Readiness row. The outline-coverage gate (§7c) is
                            // enforced by the shared engine via coverageOk: a
                            // section that skips too much of its official outline
                            // abstains even when it has enough responses.
                            val readinessValue =
                                if (r != null && r.hasScore && r.coverageOk) {
                                    val score = Math.round(r.score)
                                    val lo = Math.round(r.scoreLow)
                                    val hi = Math.round(r.scoreHigh)
                                    "$score  ($lo–$hi) · ${r.confidence} confidence"
                                } else if (r != null && r.hasScore && !r.coverageOk) {
                                    val covered = r.outlineCovered
                                    val total = r.outlineTotal
                                    "No score: only $covered/$total outline topics covered"
                                } else {
                                    "Not enough data yet"
                                }
                            addView(metricRow("Readiness", readinessValue))
                        },
                    )
                },
            )
        }

        // Projected overall score, summarised above the per-section cards. The
        // score, range, and abstain rule all come from the shared Rust engine
        // (single source of truth for the 205–805 total formula).
        sectionsContainer.addView(overallCard(readiness.overall), 0)
    }

    /** Card showing the engine's projected overall score (+range/margin). */
    private fun overallCard(overall: anki.gmat.OverallReadiness): View =
        card().apply {
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(
                        TextView(context).apply {
                            text = "Projected overall score"
                            setTextColor(themeColor(androidx.appcompat.R.attr.colorPrimary))
                            setTypeface(typeface, Typeface.BOLD)
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        },
                    )
                    if (!overall.hasScore) {
                        addView(
                            TextView(context).apply {
                                text =
                                    "Needs all ${overall.sectionsTotal} section scores " +
                                    "(${overall.sectionsScored}/${overall.sectionsTotal} ready)"
                                alpha = 0.7f
                                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                                setPadding(0, dp(6), 0, 0)
                            },
                        )
                    } else {
                        val moe =
                            maxOf(
                                overall.score - overall.scoreLow,
                                overall.scoreHigh - overall.score,
                            )
                        addView(
                            TextView(context).apply {
                                text = "${overall.score}"
                                setTypeface(typeface, Typeface.BOLD)
                                setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
                                setPadding(0, dp(4), 0, 0)
                            },
                        )
                        addView(
                            TextView(context).apply {
                                text = "range ${overall.scoreLow}–${overall.scoreHigh} (±$moe) · 205–805 scale"
                                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                            },
                        )
                        addView(
                            TextView(context).apply {
                                text =
                                    "(Quant + Verbal + Data Insights − 180) × 20/3 + 205, " +
                                    "rounded to the nearest 5"
                                alpha = 0.7f
                                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                                setPadding(0, dp(3), 0, 0)
                            },
                        )
                    }
                },
            )
        }
}
