// SPDX-License-Identifier: GPL-3.0-or-later
package com.ichi2.anki.gmat

import android.content.Context
import anki.import_export.importAnkiPackageOptions
import com.ichi2.anki.CollectionManager.withCol
import timber.log.Timber
import java.io.File

/**
 * The full GMAT deck (GMAT::Terms + GMAT::Practice, ~7k cards, no scheduling) ships
 * as a bundled asset (assets/gmat.apkg) and is imported once into a fresh collection,
 * so a new user has the deck "built in" without a manual import.
 *
 * Mirrors the desktop seeding in `qt/aqt/gmat.py` (same .apkg, same one-time guard
 * key). The guard is a synced config flag rather than "are the GMAT decks present?",
 * so a user who deletes the deck on purpose won't have it silently re-added.
 */
object GmatBuiltinDeck {
    private const val SEEDED_KEY = "gmat_builtin_deck_seeded"
    private const val ASSET_NAME = "gmat.apkg"

    /**
     * Import the bundled deck once. Safe to call on every startup: a no-op after the
     * first successful import. Returns true only when it actually imported (so the
     * caller can refresh the deck list).
     */
    suspend fun seedIfNeeded(context: Context): Boolean {
        if (withCol { config.get<Boolean>(SEEDED_KEY) ?: false }) return false
        val cached = copyAssetToCache(context) ?: return false
        try {
            withCol {
                importAnkiPackage(
                    cached.absolutePath,
                    importAnkiPackageOptions {
                        withScheduling = false
                        withDeckConfigs = true
                        mergeNotetypes = true
                    },
                )
                config.set(SEEDED_KEY, true)
            }
        } catch (e: Exception) {
            Timber.w(e, "GMAT built-in deck import failed")
            return false
        } finally {
            cached.delete()
        }
        Timber.i("Seeded built-in GMAT deck")
        return true
    }

    private fun copyAssetToCache(context: Context): File? =
        try {
            val out = File(context.cacheDir, ASSET_NAME)
            context.assets.open(ASSET_NAME).use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
            out
        } catch (e: Exception) {
            Timber.w(e, "Could not copy $ASSET_NAME from assets")
            null
        }
}
