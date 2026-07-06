/*
 *  Copyright (c) 2026 CATalyst GMAT Prep
 *
 *  This program is free software; you can redistribute it and/or modify it under
 *  the terms of the GNU General Public License as published by the Free Software
 *  Foundation; either version 3 of the License, or (at your option) any later
 *  version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT ANY
 *  WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 *  PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License along with
 *  this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.ichi2.anki.gmat

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView

/**
 * A single-item [RecyclerView.Adapter] that renders a "how to tag GMAT cards" help
 * box. It is concatenated after the deck-list adapter (via `ConcatAdapter`) so it
 * scrolls in just below the decks on the deck-list screen, giving anyone who adds
 * their own cards the tagging convention they should follow. Mirrors the desktop
 * deck-browser box.
 */
class GmatTagHelpFooterAdapter : RecyclerView.Adapter<GmatTagHelpFooterAdapter.ViewHolder>() {
    class ViewHolder(
        view: View,
    ) : RecyclerView.ViewHolder(view)

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): ViewHolder = ViewHolder(buildView(parent.context))

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
    ) = Unit

    override fun getItemCount(): Int = 1

    private fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun Context.accentColor(): Int {
        val tv = TypedValue()
        theme.resolveAttribute(androidx.appcompat.R.attr.colorPrimary, tv, true)
        return if (tv.resourceId != 0) getColor(tv.resourceId) else tv.data
    }

    /** A section label above a selectable monospace block of example tags. */
    private fun tagExample(
        context: Context,
        section: String,
        tags: String,
    ): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, context.dp(10), 0, 0)
            addView(
                TextView(context).apply {
                    text = section
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(context.accentColor())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                },
            )
            addView(
                TextView(context).apply {
                    text = tags
                    typeface = Typeface.MONOSPACE
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextIsSelectable(true)
                },
            )
        }

    private fun buildView(context: Context): View =
        MaterialCardView(context).apply {
            radius = context.dp(18).toFloat()
            cardElevation = context.dp(1).toFloat()
            layoutParams =
                RecyclerView
                    .LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT,
                        RecyclerView.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        // Extra bottom margin so the card can scroll clear of the floating
                        // add-card FAB and the pinned "studied today" summary line.
                        setMargins(context.dp(12), context.dp(6), context.dp(12), context.dp(88))
                    }
            setContentPadding(context.dp(16), context.dp(14), context.dp(16), context.dp(14))
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(
                        TextView(context).apply {
                            text = "Adding your own cards"
                            setTypeface(typeface, Typeface.BOLD)
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                        },
                    )
                    addView(
                        TextView(context).apply {
                            text =
                                "Tag every new card with a hierarchical GMAT:: tag so it counts " +
                                "toward the right section. Format: GMAT::<Section>::<Subtopic>."
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                            setPadding(0, context.dp(6), 0, 0)
                        },
                    )
                    addView(
                        tagExample(
                            context,
                            "Quant",
                            "GMAT::Quant::ProblemSolving\nGMAT::Quant::Algebra\nGMAT::Quant::Geometry",
                        ),
                    )
                    addView(
                        tagExample(
                            context,
                            "Verbal",
                            "GMAT::Verbal::CriticalReasoning\nGMAT::Verbal::ReadingComprehension\n" +
                                "GMAT::Verbal::SentenceCorrection",
                        ),
                    )
                    addView(
                        tagExample(
                            context,
                            "Data Insights",
                            "GMAT::DataInsights::DataSufficiency",
                        ),
                    )
                    addView(
                        TextView(context).apply {
                            text =
                                "Put term flashcards in the GMAT::Terms deck and practice MCQs in " +
                                "GMAT::Practice. A card with no GMAT::<Section> tag won't count " +
                                "toward any section's scores."
                            alpha = 0.7f
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                            setPadding(0, context.dp(10), 0, 0)
                        },
                    )
                },
            )
        }
}
