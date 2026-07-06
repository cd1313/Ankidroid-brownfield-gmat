// SPDX-License-Identifier: GPL-3.0-or-later
package com.ichi2.anki.gmat

import android.content.Context
import com.ichi2.anki.common.preferences.sharedPrefs
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * AI layer for GMAT features — a Kotlin port of the desktop `qt/aqt/gmat_ai.py`.
 *
 * Grades typed recall of `GMAT::Terms` cards and powers the MCQ peer features
 * (guidance on wrong answers, and the "correct the peer" game). Talks to OpenAI's
 * chat-completions API over OkHttp in JSON mode at temperature 0.
 *
 * Android has no Python/`.env`, so the key + model + master toggle live in
 * [SharedPreferences] (set on the AI settings screen). Every call is **fail-safe**:
 * a missing key / network error / bad response returns `null` so the caller falls
 * back to normal self-rating. The app always works with AI off.
 *
 * All calls block on the network — invoke them from an IO coroutine (e.g.
 * `withContext(Dispatchers.IO)`), never the main thread.
 */
object GmatAi {
    const val PREF_AI_ENABLED = "gmat_ai_enabled"
    const val PREF_API_KEY = "gmat_openai_api_key"
    const val PREF_MODEL = "gmat_ai_model"

    private const val DEFAULT_MODEL = "gpt-4o-mini"
    private const val OPENAI_URL = "https://api.openai.com/v1/chat/completions"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    // Anki answer-button eases (1=Again … 4=Easy).
    private val EASE = mapOf("again" to 1, "hard" to 2, "good" to 3, "easy" to 4)

    // ---- configuration (SharedPreferences) ---------------------------------

    fun apiKey(context: Context): String? =
        context
            .sharedPrefs()
            .getString(PREF_API_KEY, null)
            ?.trim()
            ?.ifEmpty { null }

    fun model(context: Context): String =
        context
            .sharedPrefs()
            .getString(PREF_MODEL, null)
            ?.trim()
            ?.ifEmpty { null } ?: DEFAULT_MODEL

    /** The hosted Firebase proxy is available (users get AI with no key of their own). */
    fun proxyConfigured(): Boolean = GmatFirebase.configured()

    /** AI can run: the user set their own key, or the hosted proxy is configured. */
    fun aiAvailable(context: Context): Boolean = apiKey(context) != null || proxyConfigured()

    /**
     * The master toggle is on. Defaults **on** when the proxy is configured (so a
     * fresh install has AI out of the box); otherwise defaults off. A stored value
     * (user flipped it) always wins.
     */
    fun aiToggleOn(context: Context): Boolean = context.sharedPrefs().getBoolean(PREF_AI_ENABLED, proxyConfigured())

    /** AI is usable: master toggle on AND a key or the proxy is available. */
    fun aiEnabled(context: Context): Boolean = aiToggleOn(context) && aiAvailable(context)

    // ---- data models -------------------------------------------------------

    /**
     * Outcome of an AI grade. [correct] is strict (only a full `correct` verdict).
     * [rating] is the AI-chosen Anki button; [ease] maps it to the 1–4 answer value.
     */
    data class GradeResult(
        val correct: Boolean,
        val verdict: String, // "correct" | "partial" | "incorrect"
        val rationale: String,
        val rating: String, // "again" | "hard" | "good" | "easy"
    ) {
        val ease: Int get() = EASE[rating] ?: if (correct) 3 else 1
    }

    /** The peer's first-person feedback plus an optional sanitized inline SVG ("" when none). */
    data class PeerReply(
        val text: String,
        val svg: String = "",
    )

    /** A plausible-but-wrong peer solution: the wrong option letter + its flawed reasoning. */
    data class PeerFlaw(
        val choice: String,
        val reasoning: String,
    )

    /** Judgement of the student's critique in "correct the peer". */
    data class Critique(
        val foundFlaw: Boolean,
        val feedback: String,
    )

    // ---- grading -----------------------------------------------------------

    /**
     * Grade [answer] against [expected] for meaning. Returns `null` on any failure
     * so the caller can fall back to self-rating.
     */
    fun grade(
        context: Context,
        question: String,
        expected: String,
        answer: String,
    ): GradeResult? {
        if (!aiAvailable(context)) return null
        val trimmed = answer.trim()
        if (trimmed.isEmpty()) {
            return GradeResult(false, "incorrect", "No answer was entered.", "again")
        }
        if (expected.trim().isEmpty()) return null

        val user =
            "Prompt (front of card):\n$question\n\n" +
                "Expected answer (source of truth):\n$expected\n\n" +
                "Student's answer:\n$trimmed\n\n" +
                "Grade the student's answer."
        val parsed = chatJson(context, SYSTEM_PROMPT, user) ?: return null

        val verdict = parsed.optString("verdict").trim().lowercase()
        if (verdict !in setOf("correct", "partial", "incorrect")) return null
        val rationale = parsed.optString("rationale").trim()
        var rating = parsed.optString("rating").trim().lowercase()
        if (rating !in EASE) {
            rating = mapOf("correct" to "good", "partial" to "hard", "incorrect" to "again")[verdict]!!
        }
        return GradeResult(correct = verdict == "correct", verdict = verdict, rationale = rationale, rating = rating)
    }

    // ---- peer studier (MCQ) ------------------------------------------------

    /**
     * A peer's first-person take on why the student's MCQ answer was wrong,
     * grounded in the card's explanation, with an optional inline SVG diagram.
     */
    fun peerExplain(
        context: Context,
        question: String,
        options: List<Pair<String, String>>,
        correctLetter: String,
        chosenLetter: String,
        explanation: String,
    ): PeerReply? {
        val user =
            "Question:\n$question\n\nOptions:\n${formatOptions(options)}\n\n" +
                "Correct answer: $correctLetter\nStudent chose: $chosenLetter\n\n" +
                "Reference explanation:\n${explanation.ifBlank { "(none provided)" }}\n\n" +
                "Explain the student's mistake, with a diagram only if it truly helps."
        val data = chatJson(context, PEER_EXPLAIN_PROMPT, user) ?: return null
        val text = data.optString("guidance").trim()
        if (text.isEmpty()) return null
        return PeerReply(text = text, svg = sanitizeSvg(data.optString("svg")))
    }

    /**
     * A plausible-but-wrong peer solution for the "correct the peer" mode.
     * Guaranteed to pick a wrong option (or `null`).
     */
    fun peerFlawedSolution(
        context: Context,
        question: String,
        options: List<Pair<String, String>>,
        correctLetter: String,
        explanation: String,
    ): PeerFlaw? {
        val user =
            "Question:\n$question\n\nOptions:\n${formatOptions(options)}\n\n" +
                "Correct answer (avoid this one): $correctLetter\n\n" +
                "Reference explanation:\n${explanation.ifBlank { "(none provided)" }}\n\n" +
                "Give a confident but flawed solution ending on a wrong option."
        val data = chatJson(context, PEER_FLAW_PROMPT, user) ?: return null
        val choice =
            data
                .optString("choice")
                .trim()
                .uppercase()
                .take(1)
        val reasoning = data.optString("reasoning").trim()
        if (choice.isEmpty() || reasoning.isEmpty() || choice == correctLetter.trim().uppercase()) return null
        return PeerFlaw(choice = choice, reasoning = reasoning)
    }

    /** Judge the student's critique of the peer's flawed solution. `null` when unavailable. */
    fun critiqueCheck(
        context: Context,
        question: String,
        correctLetter: String,
        explanation: String,
        flawedReasoning: String,
        studentCritique: String,
    ): Critique? {
        val user =
            "Question:\n$question\n\nCorrect answer: $correctLetter\n\n" +
                "Reference explanation:\n${explanation.ifBlank { "(none provided)" }}\n\n" +
                "Peer's flawed solution:\n$flawedReasoning\n\n" +
                "Student's critique:\n$studentCritique\n\n" +
                "Did the student correctly identify the flaw or the right approach?"
        val data = chatJson(context, CRITIQUE_PROMPT, user) ?: return null
        return Critique(foundFlaw = data.optBoolean("found_flaw"), feedback = data.optString("feedback").trim())
    }

    // ---- helpers -----------------------------------------------------------

    private fun formatOptions(options: List<Pair<String, String>>): String =
        options.joinToString("\n") { (letter, text) -> "$letter. $text" }

    /**
     * Shared JSON-mode chat call. Returns the parsed object, or `null` on any
     * failure (no key, network error, bad JSON). Blocks — call from IO.
     */
    private fun chatJson(
        context: Context,
        systemPrompt: String,
        userPrompt: String,
        timeoutSecs: Long = 15,
    ): JSONObject? {
        // Route to OpenAI directly when the user supplied their own key (BYOK);
        // otherwise go through the Firebase-backed proxy (anonymous auth + App
        // Check), which holds the OpenAI key server-side. Returns null if neither
        // is available so the caller falls back to normal behaviour.
        val key = apiKey(context)
        val url: String
        val authHeaders = mutableMapOf<String, String>()
        if (key != null) {
            url = OPENAI_URL
            authHeaders["Authorization"] = "Bearer $key"
        } else if (GmatFirebase.configured()) {
            val idToken = GmatFirebase.idTokenBlocking(context) ?: return null
            url = GmatFirebase.proxyUrl
            authHeaders["Authorization"] = "Bearer $idToken"
            authHeaders["X-Gmat-Platform"] = "android"
            GmatFirebase.appCheckTokenBlocking(context)?.let { authHeaders["X-Firebase-AppCheck"] = it }
        } else {
            return null
        }
        return try {
            val payload =
                JSONObject()
                    .put("model", model(context))
                    .put(
                        "messages",
                        org.json
                            .JSONArray()
                            .put(JSONObject().put("role", "system").put("content", systemPrompt))
                            .put(JSONObject().put("role", "user").put("content", userPrompt)),
                    ).put("temperature", 0)
                    .put("response_format", JSONObject().put("type", "json_object"))
            val client =
                OkHttpClient
                    .Builder()
                    .connectTimeout(timeoutSecs, TimeUnit.SECONDS)
                    .writeTimeout(timeoutSecs, TimeUnit.SECONDS)
                    .readTimeout(timeoutSecs, TimeUnit.SECONDS)
                    .build()
            val request =
                Request
                    .Builder()
                    .url(url)
                    .apply { authHeaders.forEach { (k, v) -> header(k, v) } }
                    .post(payload.toString().toRequestBody(JSON))
                    .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Timber.w("GmatAi: OpenAI HTTP %d", resp.code)
                    return null
                }
                val bodyText = resp.body.string()
                val content =
                    JSONObject(bodyText)
                        .getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                val parsed = JSONObject(content)
                parsed
            }
        } catch (e: Exception) {
            Timber.w(e, "GmatAi: chat call failed")
            null
        }
    }

    /**
     * Keep a single inline `<svg>` only, stripping scripts, event handlers, and
     * external references. Returns "" if it doesn't look like a safe SVG.
     * Mirrors desktop `_sanitize_svg`.
     */
    fun sanitizeSvg(svg: String?): String {
        val s0 = (svg ?: "").trim()
        val low = s0.lowercase()
        if (!low.startsWith("<svg") || !low.contains("</svg>") || s0.length > 20000) return ""
        var s = s0
        val dotall = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        s = s.replace(Regex("<script.*?</script>", dotall), "")
        s = s.replace(Regex("<foreignobject.*?</foreignobject>", dotall), "")
        s = s.replace(Regex("""\son\w+\s*=\s*("[^"]*"|'[^']*')""", RegexOption.IGNORE_CASE), "")
        s = s.replace(Regex("""\s(?:xlink:href|href)\s*=\s*("[^"]*"|'[^']*')""", RegexOption.IGNORE_CASE), "")
        return s
    }

    // ---- prompts (verbatim from desktop gmat_ai.py) ------------------------

    private val SYSTEM_PROMPT =
        "You grade a student's typed recall of a flashcard term AND choose the Anki " +
            "spaced-repetition rating on their behalf.\n" +
            "GRADING: Grade generously — reward understanding, not exact or complete " +
            "phrasing. The expected answer may contain the term's definition PLUS example " +
            "sentences or usage illustrations; judge ONLY whether the student captured the " +
            "core meaning of the term. Do NOT penalize brevity, informal wording, " +
            "spelling/typos, synonyms, or omitting examples and minor nuance. Use these " +
            "thresholds:\n" +
            "- \"correct\": conveys the core meaning, even if brief, loosely worded, or " +
            "missing minor detail. When torn between correct and partial, choose correct.\n" +
            "- \"partial\": the general idea is present but a KEY part of the meaning is " +
            "wrong or missing.\n" +
            "- \"incorrect\": the meaning is essentially wrong, unrelated, or blank.\n" +
            "The expected answer is the source of truth; do not use outside knowledge to " +
            "override it.\n" +
            "RATING: choose exactly one Anki/FSRS button. Their meanings drive how soon " +
            "the card is seen again:\n" +
            "- \"again\": failed recall — meaning wrong, missing, or not remembered. The " +
            "card lapses and reappears almost immediately.\n" +
            "- \"hard\": recalled but barely — partially correct, vague, or an incomplete/" +
            "struggling definition. The next interval grows only slightly.\n" +
            "- \"good\": correct recall with normal effort — the definitional meaning is " +
            "right. This is the default for a correct answer.\n" +
            "- \"easy\": a clearly correct, solid answer that captures the core meaning " +
            "with no errors. It need not be perfectly worded or exhaustive, but it should " +
            "be more than a bare-minimum pass. Still don't give it to a shaky, vague, or " +
            "partial answer.\n" +
            "Map the rating from the verdict, favouring the more forgiving option: " +
            "correct->good, but use easy when the correct answer is clearly solid and " +
            "confident (not just barely sufficient); partial->hard; incorrect->again. " +
            "Never give 'again' to an answer that shows real understanding of the term.\n" +
            "RATIONALE: write 1-2 sentences addressed to the student (\"you\") explaining " +
            "WHY you gave that rating — how their answer compared to the definition (what " +
            "they got right, missed, or confused). Do NOT restate the full definition; the " +
            "card already shows it.\n" +
            "Respond with ONLY compact JSON of the form " +
            "{\"verdict\":\"correct|partial|incorrect\",\"rating\":\"again|hard|good|easy\"," +
            "\"rationale\":\"<1-2 sentences explaining the rating>\"}."

    private val PEER_EXPLAIN_PROMPT =
        "You are a friendly fellow GMAT student — a study buddy, NOT a teacher — " +
            "reacting to a question your friend just got wrong. Speak in the FIRST PERSON " +
            "with a warm, casual, encouraging voice, like texting a classmate. Start by " +
            "relating to how tricky it was, then walk through how YOU approached it — " +
            "(\"that one was rough! here's how I tried it: …\") — and gently point out " +
            "where their choice slips up. Share it as a peer thinking out loud, not a " +
            "lecture. Base your walkthrough ONLY on the reference explanation/answer; do " +
            "not invent facts. Keep it to 2-4 sentences.\n" +
            "IF (and only if) a simple picture would genuinely help — a geometry figure, " +
            "a number line, a small bar chart, or a tiny table — include a minimal, " +
            "self-contained inline SVG in `svg` (a single <svg …>…</svg>, roughly " +
            "360x260 max, readable in both light and dark themes, NO <script>, no " +
            "external images or links). For questions where a diagram adds nothing (most " +
            "verbal / plain-text ones), set \"svg\" to an empty string.\n" +
            "Respond with ONLY compact JSON: " +
            "{\"guidance\":\"<your peer message>\",\"svg\":\"<inline SVG or empty string>\"}."

    private val PEER_FLAW_PROMPT =
        "Role-play a fellow GMAT student who solves a multiple-choice question but " +
            "makes ONE realistic, plausible mistake and arrives at a WRONG answer. Pick a " +
            "wrong option (never the correct one) and write a short first-person solution " +
            "(2-4 sentences) that sounds confident but contains the flaw — do not reveal " +
            "that it is wrong. Base it on the real question; the correct answer is given " +
            "so you can avoid it. Respond with ONLY compact JSON: " +
            "{\"choice\":\"<wrong option letter>\",\"reasoning\":\"<2-4 sentence flawed solution>\"}."

    private val CRITIQUE_PROMPT =
        "A student is playing 'correct the peer': a peer gave a flawed solution to a " +
            "GMAT question and the student critiques it. To succeed the student must do " +
            "BOTH: (1) give the correct answer or approach, AND (2) actually EXPLAIN it — " +
            "why the peer's reasoning is wrong and/or why the correct approach works.\n" +
            "Set found_flaw=true ONLY when both are present. If the student merely states " +
            "an answer with no real reasoning (e.g. \"the answer is A\", \"it's B\", just a " +
            "letter, or a vague restatement with no justification), set found_flaw=false " +
            "and, in the feedback, tell them they need to explain WHY — not just name the " +
            "answer. Be encouraging but honest, and ground your judgement in the reference " +
            "explanation. Respond with ONLY compact JSON: " +
            "{\"found_flaw\":true|false,\"feedback\":\"<2-3 sentences>\"}."
}
