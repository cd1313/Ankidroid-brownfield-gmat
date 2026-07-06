// SPDX-License-Identifier: GPL-3.0-or-later
package com.ichi2.anki.gmat

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.auth.FirebaseAuth
import com.ichi2.anki.BuildConfig
import timber.log.Timber

/**
 * Firebase glue for the GMAT AI proxy. Lets the app authenticate as an anonymous
 * Firebase user and attest with App Check (Play Integrity) so it can call the
 * Cloud Function that holds the OpenAI key — no per-user key required.
 *
 * Config comes from [BuildConfig] fields (set via gradle properties), not a
 * `google-services.json`, so the app still builds/runs unconfigured: when the
 * fields are empty [configured] is false, the proxy is skipped, and AI simply
 * falls back to BYOK / off. All token calls are **blocking** (via [Tasks.await])
 * and must be invoked from a background thread (the AI calls already are).
 */
object GmatFirebase {
    private const val APP_NAME = "gmat"

    @Volatile private var initialized = false

    fun configured(): Boolean =
        BuildConfig.GMAT_AI_PROXY_URL.isNotEmpty() &&
            BuildConfig.GMAT_FIREBASE_API_KEY.isNotEmpty() &&
            BuildConfig.GMAT_FIREBASE_APP_ID.isNotEmpty() &&
            BuildConfig.GMAT_FIREBASE_PROJECT_ID.isNotEmpty()

    val proxyUrl: String get() = BuildConfig.GMAT_AI_PROXY_URL

    @Synchronized
    private fun app(context: Context): FirebaseApp {
        val existing = FirebaseApp.getApps(context).firstOrNull { it.name == APP_NAME }
        if (existing != null && initialized) return existing
        val app =
            existing
                ?: FirebaseApp.initializeApp(
                    context.applicationContext,
                    FirebaseOptions
                        .Builder()
                        .setApiKey(BuildConfig.GMAT_FIREBASE_API_KEY)
                        .setApplicationId(BuildConfig.GMAT_FIREBASE_APP_ID)
                        .setProjectId(BuildConfig.GMAT_FIREBASE_PROJECT_ID)
                        .build(),
                    APP_NAME,
                )
        runCatching {
            FirebaseAppCheck
                .getInstance(app)
                .installAppCheckProviderFactory(gmatAppCheckProviderFactory())
        }.onFailure { Timber.w(it, "GmatFirebase: App Check init failed") }
        initialized = true
        return app
    }

    /** Anonymous Firebase ID token (signing in if needed); null on failure. */
    fun idTokenBlocking(context: Context): String? =
        try {
            val auth = FirebaseAuth.getInstance(app(context))
            val user = auth.currentUser ?: Tasks.await(auth.signInAnonymously()).user
            user?.let { Tasks.await(it.getIdToken(false)).token }
        } catch (e: Exception) {
            Timber.w(e, "GmatFirebase: idToken failed")
            null
        }

    /** Play Integrity App Check token; null on failure. */
    fun appCheckTokenBlocking(context: Context): String? =
        try {
            Tasks.await(FirebaseAppCheck.getInstance(app(context)).getAppCheckToken(false)).token
        } catch (e: Exception) {
            Timber.w(e, "GmatFirebase: appCheck token failed")
            null
        }
}
