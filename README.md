# CATalyst GMAT Prep — Android

A fork of [AnkiDroid](https://github.com/ankidroid/Anki-Android) turned into a focused **GMAT study
app** for Android. It keeps AnkiDroid's spaced-repetition core and adds the same GMAT layer as the
[desktop fork](../anki-brownfield-gmat/README.md): objectively-graded multiple-choice practice, an
honest per-section readiness dashboard with a projected **overall 205–805 score**, an adaptive
question recommender, and an optional AI study layer.

Crucially, the scoring is **not reimplemented on the phone**. This app embeds the desktop fork's
Rust engine (compiled to an Android `.so` via `Anki-Android-Backend`/rsdroid), so the dashboard
numbers, the overall total, the outline-coverage rules, and the practice recommendations are
computed by the **exact same code** that runs on desktop. Study on either device and sync between
them.

---

## The exam we're preparing for

**GMAT Focus Edition** — three sections (Quantitative, Verbal, Data Insights), 45 minutes each,
computer-adaptive. Section scores are **60–90**; the total is **205–805** in 10-point steps. Content
is organized under three section tags (`GMAT::Quant`, `GMAT::Verbal`, `GMAT::DataInsights`).

---

## What this fork adds (beyond stock AnkiDroid)

### GMAT dashboard — three honest scores + a projected total

Opened from the DeckPicker overflow menu → **GMAT**, the dashboard shows, per section, and never
blended:

- **Memory** — term recall from FSRS retrievability (studied-cards recall with a range, plus a
  coverage-aware whole-section number).
- **Performance** — per-section IRT ability **θ** (3PL, EAP) from your timed MCQ answers.
- **Readiness** — a projected section score (**60–90**) with a range and confidence, combining
  accuracy with a pacing check. A section that covers **too little of the official GMAT outline**
  (< 50% of its question types present in the deck) abstains — the exact spec-§7c rule the desktop
  app uses, enforced by the shared engine.
- **Projected overall score** — the three section scores rolled into a single **GMAT Focus total
  (205–805)** with a ± margin of error, via `(Quant + Verbal + Data Insights − 180) × 20⁄3 + 205`.
  It **abstains until all three sections have a readiness score**, so a coverage- or data-suppressed
  section can't inflate the total.

Each score has a give-up rule: below its data threshold it shows _"Not enough data yet"_ instead of
a misleading number.

### Objective MCQ practice + adaptive recommender

Practice questions (the **"GMAT MCQ"** note type) are **graded objectively by the engine** against
the stored answer — you never self-grade practice. A **2-minute per-question countdown** mirrors the
real exam's pace and feeds the readiness pacing check. The practice pool is **weakness-first**: it
uses your IRT scores to prioritize your lowest section and pick questions near your ability, with an
exploration bonus for unseen items (and a plain random draw when there's no data yet). MCQ attempts
are logged as non-scheduling entries, so they **never** contaminate the FSRS Memory score.

### Optional AI study layer (`GmatAi.kt`)

A Kotlin port of the desktop AI layer, off by default, toggled on the dashboard. Three features:

- **Semantic term grading** — types your recall of a `GMAT::Terms` card and has an LLM grade it for
  meaning, recommend an FSRS button, and explain why.
- **Study peer** — on a wrong MCQ, a first-person peer explains the mistake, grounded only in the
  card's stored explanation.
- **Correct the Peer** — a reciprocal-teaching game where you critique a peer's deliberately-wrong
  solution.

You can supply **your own OpenAI key**, or — when the build is configured for it — the app calls a
**Firebase-proxied** endpoint (anonymous auth + Play Integrity App Check) so you don't have to paste
a key. Every AI call is **fail-safe**: a missing key / network error / bad response falls back to
normal self-rating, and the readiness scores never touch the LLM.

### Bundled deck + sync

The full GMAT deck (`GMAT::Terms` + `GMAT::Practice`, ~7k cards) ships as an asset and is
**imported once** on first run (`GmatBuiltinDeck.kt`), so a new user has the deck built in. Sync
with the desktop app through a custom sync server (see
[`docs/gmat/SYNC.md`](../anki-brownfield-gmat/docs/gmat/SYNC.md) in the desktop fork).

Everything AnkiDroid already does — FSRS scheduling, night mode, TTS, MathJax, widgets, statistics —
still works.

---

## Building & running

Three sibling repos are involved (all cloned side-by-side under the same parent directory):

| Repo | Role |
| --- | --- |
| `anki-brownfield-gmat` | the desktop fork — **owns the shared Rust GMAT engine + proto** |
| `Anki-Android-Backend` | rsdroid — compiles that Rust to an Android `.aar` and generates the GMAT RPCs into `GeneratedBackend.kt` (its `anki` submodule points at the desktop fork) |
| `Ankidroid-brownfield-gmat` | **this repo** — the Kotlin app |

### 1. Build the backend `.aar` (whenever the engine or `.proto` changes)

```sh
export ANDROID_HOME="$HOME/Library/Android/sdk"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/$(toml get ../Anki-Android-Backend/gradle/libs.versions.toml versions.ndk --raw)"
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
cd ../Anki-Android-Backend && ./build.sh   # -> rsdroid/build/outputs/aar/rsdroid-release.aar
```

### 2. Build & install the app

Point the app at the locally-built backend by adding `local_backend=true` to `local.properties`
(already set here), then:

```sh
export ANDROID_HOME="$HOME/Library/Android/sdk"
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :AnkiDroid:assemblePlayDebug     # -> AnkiDroid/build/outputs/apk/play/debug/AnkiDroid-play-arm64-v8a-debug.apk
adb install -r -d AnkiDroid/build/outputs/apk/play/debug/AnkiDroid-play-arm64-v8a-debug.apk
```

The debug build's application id is `com.ichi2.anki.debug`.

### 3. In the app

- **Settings → Reviewer → New reviewer** (pref key `newReviewerOptions`) must be **on** for the GMAT
  reviewer features.
- Open the dashboard from the **DeckPicker overflow (⋮) → GMAT**.
- _(Optional)_ Turn on **AI features** on the dashboard and either set your OpenAI key or rely on the
  Firebase proxy (if the build is configured for it). Leave it off to run entirely offline.

> The readiness scores work fully offline. Only the AI study layer needs a key/network.

---

## Where the GMAT code lives

- `AnkiDroid/src/main/java/com/ichi2/anki/GmatDashboardActivity.kt` — the dashboard (three scores +
  projected overall + coverage), rendered from the shared engine's `estimate_readiness` /
  `get_topic_mastery` RPCs.
- `AnkiDroid/src/main/java/com/ichi2/anki/gmat/` — `GmatAi.kt` (AI layer), `GmatFirebase.kt`
  (Firebase AI proxy), `GmatPractice.kt` (practice pool), `GmatBuiltinDeck.kt` (bundled-deck import),
  `CorrectPeerActivity.kt` ("Correct the Peer" game), `GmatTagHelpFooterAdapter.kt` (tagging help).
- The engine itself is **not here** — it's `rslib/src/gmat/` in the desktop fork, shipped via the
  rsdroid `.aar`.

---

## Credits & license

This project is a fork of **[AnkiDroid](https://github.com/ankidroid/Anki-Android)**, itself a port
of **[Anki](https://github.com/ankitects/anki)** (by Damien Elmes and contributors). All original
AnkiDroid and Anki copyrights, credits, and license terms are retained; the GMAT additions in this
fork are released under the same terms.

- [GPL-3.0 License](https://github.com/ankidroid/Anki-Android/blob/main/COPYING) — the AnkiDroid app
- [AGPL-3.0 License](https://github.com/ankitects/anki/blob/main/LICENSE) — the Anki back-end
- [LGPL-3.0 License](https://github.com/ankidroid/Anki-Android/blob/main/api/COPYING.LESSER) — the
  AnkiDroid API

Upstream AnkiDroid: user manual at <https://docs.ankidroid.org/> · wiki at
<https://github.com/ankidroid/Anki-Android/wiki> · thanks to AnkiDroid's contributors, translators,
and backers, whose work this fork builds on.
