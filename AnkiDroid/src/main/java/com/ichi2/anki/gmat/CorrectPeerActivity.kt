// SPDX-License-Identifier: GPL-3.0-or-later
package com.ichi2.anki.gmat

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.LinearLayout
import com.google.android.material.appbar.MaterialToolbar
import com.ichi2.anki.AnkiActivity
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.launchCatchingTask
import com.ichi2.anki.libanki.CardId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Correct the Peer" — a reciprocal-teaching game (mobile port of the desktop
 * [qt/aqt/gmat_peer.py] dialog). The AI presents a plausible-but-wrong solution to
 * a GMAT practice MCQ; the student must give the right answer AND explain why the
 * peer is wrong; the AI judges the critique.
 *
 * Self-contained: a WebView hosts the HTML/JS "blob" (reused from desktop, with the
 * `pycmd` transport swapped for a JS↔Kotlin bridge). Requires AI to be enabled
 * (master toggle + OpenAI key). All AI calls run off the main thread and fail safe.
 */
class CorrectPeerActivity : AnkiActivity() {
    private lateinit var web: WebView

    // The card being critiqued (kept server-side; the answer never goes to JS until judged).
    private data class Challenge(
        val cardId: CardId,
        val correct: String,
        val explanation: String,
        val question: String,
        val flawReasoning: String,
    )

    private var current: Challenge? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (showedActivityFailedScreen(savedInstanceState)) {
            return
        }
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.addJavascriptInterface(Bridge(), "AndroidPeer")

        val toolbar =
            MaterialToolbar(this).apply {
                val tv = TypedValue()
                theme.resolveAttribute(androidx.appcompat.R.attr.colorPrimary, tv, true)
                setBackgroundColor(tv.data)
                title = "Correct the Peer"
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
                    web,
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
                )
            }
        setContentView(root)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        web.loadDataWithBaseURL(null, HTML, "text/html", "utf-8", null)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun eval(js: String) = web.post { web.evaluateJavascript(js, null) }

    /** JS-callable bridge. Methods arrive on a WebView worker thread, so each hops
     *  onto a coroutine for `withCol` + the (blocking) OpenAI call. */
    private inner class Bridge {
        @JavascriptInterface
        fun serve() = this@CorrectPeerActivity.serveChallenge()

        @JavascriptInterface
        fun submit(critique: String) = this@CorrectPeerActivity.judge(critique)
    }

    private fun serveChallenge() {
        eval("setBusy('Peer is solving…');")
        launchCatchingTask {
            val cids = withCol { findCards(PRACTICE_SEARCH) }
            if (cids.isEmpty()) {
                eval("setBusy('No practice questions found in \"GMAT::Practice\".');")
                return@launchCatchingTask
            }
            // Try cards in random order until one has a usable question + options.
            var payload: JSONObject? = null
            var chosen: Challenge? = null
            for (cid in cids.shuffled()) {
                val fields = withCol { fieldMapFor(cid) } ?: continue
                val question = fields["Question"]?.trim().orEmpty()
                val options = optionList(fields)
                if (question.isEmpty() || options.isEmpty()) continue
                val correct = fields["Answer"]?.trim().orEmpty()
                val explanation = fields["Explanation"]?.trim().orEmpty()
                val flaw =
                    withContext(Dispatchers.IO) {
                        GmatAi.peerFlawedSolution(this@CorrectPeerActivity, question, options, correct, explanation)
                    }
                if (flaw == null) {
                    eval("setBusy('AI is unavailable — set your OpenAI key on the dashboard to play.');")
                    return@launchCatchingTask
                }
                chosen = Challenge(cid, correct, explanation, question, flaw.reasoning)
                payload =
                    JSONObject()
                        .put("question", question)
                        .put(
                            "options",
                            JSONArray().apply {
                                options.forEach { (l, t) -> put(JSONObject().put("letter", l).put("text", t)) }
                            },
                        ).put("peerChoice", flaw.choice)
                        .put("peerReasoning", flaw.reasoning)
                break
            }
            if (payload == null || chosen == null) {
                eval("setBusy('No usable practice questions found.');")
                return@launchCatchingTask
            }
            current = chosen
            eval("showChallenge($payload);")
        }
    }

    private fun judge(critique: String) {
        val cur = current ?: return
        eval("setBusy('Peer is considering your critique…');")
        launchCatchingTask {
            val result =
                withContext(Dispatchers.IO) {
                    GmatAi.critiqueCheck(
                        this@CorrectPeerActivity,
                        cur.question,
                        cur.correct,
                        cur.explanation,
                        cur.flawReasoning,
                        critique,
                    )
                }
            if (result == null) {
                eval("setBusy('AI is unavailable right now.');")
                return@launchCatchingTask
            }
            val data =
                JSONObject()
                    .put("found_flaw", result.foundFlaw)
                    .put("feedback", result.feedback)
                    .put("correct", cur.correct)
            eval("showFeedback($data);")
        }
    }

    /** Map field name -> value for a card's note, or null if it can't be read. */
    private fun com.ichi2.anki.libanki.Collection.fieldMapFor(cid: CardId): Map<String, String>? =
        try {
            val note = getCard(cid).note(this)
            note.notetype.fieldsNames
                .zip(note.fields)
                .toMap()
        } catch (e: Exception) {
            null
        }

    private fun optionList(fields: Map<String, String>): List<Pair<String, String>> =
        listOf("A", "B", "C", "D", "E")
            .mapNotNull { letter -> fields[letter]?.takeIf { it.isNotBlank() }?.let { letter to it } }

    companion object {
        private const val PRACTICE_SEARCH = """deck:"GMAT::Practice" note:"GMAT MCQ""""

        // Self-contained HTML/JS. Transport is the AndroidPeer JS bridge (vs pycmd on desktop).
        private val HTML =
            """
            <!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
              body { font-family: -apple-system, Roboto, system-ui, sans-serif; margin:0;
                     padding:16px; color:#241f1c; background:#fffcfa; }
              h2 { color:#d85a40; margin:0 0 10px; }
              #peer { background:#fbf3ee; border:1px solid #f5eae3; border-radius:12px;
                      padding:12px 14px; margin:12px 0; }
              #opts div { margin:2px 0; }
              textarea { width:100%; box-sizing:border-box; border-radius:10px; padding:10px;
                         border:1px solid #ddcfc6; font-size:15px; }
              button { background:#f9876f; color:#fff; border:none; border-radius:10px;
                       padding:10px 16px; font-size:15px; margin-top:10px; }
              button.secondary { background:#efe7e0; color:#241f1c; }
              .muted { opacity:.7; }
              #busy { margin-top:12px; opacity:.7; }
            </style></head><body>
              <h2>Correct the Peer 🐱</h2>
              <p class="muted">A study peer solved this question — but made a mistake. Give the
                 correct answer AND explain why the peer's reasoning is wrong.</p>
              <div id="q" style="font-weight:600;margin:12px 0;"></div>
              <div id="opts" class="muted"></div>
              <div id="peer"></div>
              <textarea id="crit" rows="4" placeholder="Give the correct answer AND explain why the peer's reasoning is wrong — naming the answer alone isn't enough."></textarea>
              <div>
                <button id="submit" onclick="submitCritique()">Submit critique</button>
                <button id="next" class="secondary" style="display:none" onclick="AndroidPeer.serve()">Next question</button>
              </div>
              <div id="feedback" style="margin-top:14px;"></div>
              <div id="busy"></div>
            <script>
              function setBusy(m){ document.getElementById('busy').textContent = m || ''; }
              function esc(s){ var d=document.createElement('div'); d.textContent=s; return d.innerHTML; }
              function showChallenge(d){
                setBusy('');
                document.getElementById('feedback').innerHTML='';
                document.getElementById('crit').value='';
                document.getElementById('crit').disabled=false;
                document.getElementById('submit').style.display='inline-block';
                document.getElementById('next').style.display='none';
                document.getElementById('q').innerHTML = esc(d.question);
                document.getElementById('opts').innerHTML =
                  d.options.map(function(o){return '<div><b>'+esc(o.letter)+'.</b> '+esc(o.text)+'</div>';}).join('');
                document.getElementById('peer').innerHTML =
                  '<div style="font-weight:700;">🐱 Peer chose '+esc(d.peerChoice)+'</div>'+
                  '<div style="margin-top:4px;">'+esc(d.peerReasoning)+'</div>';
              }
              function submitCritique(){
                var t = document.getElementById('crit').value.trim();
                if(!t){ setBusy('Write your critique first.'); return; }
                document.getElementById('submit').style.display='none';
                document.getElementById('crit').disabled=true;
                AndroidPeer.submit(t);
              }
              function showFeedback(d){
                setBusy('');
                var color = d.found_flaw ? '#2e7d32' : '#b8860b';
                var head = d.found_flaw ? '✓ Good catch' : '✗ Not quite';
                document.getElementById('feedback').innerHTML =
                  '<div style="font-weight:700;color:'+color+';">'+head+'</div>'+
                  '<div style="margin:6px 0;">'+esc(d.feedback)+'</div>'+
                  '<div class="muted"><b>Correct answer:</b> '+esc(d.correct)+'</div>';
                document.getElementById('next').style.display='inline-block';
              }
              AndroidPeer.serve();
            </script></body></html>
            """.trimIndent()
    }
}
