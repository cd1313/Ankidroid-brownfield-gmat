// SPDX-License-Identifier: GPL-3.0-or-later
package com.ichi2.anki.gmat

import com.google.firebase.appcheck.AppCheckProviderFactory
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

/**
 * Debug builds: attest with the App Check **debug** provider. Play Integrity
 * cannot produce a verdict on an emulator or an unsigned build, so this logs a
 * debug secret you allowlist in the Firebase console (App Check → Manage debug
 * tokens) to exercise the proxy while developing.
 */
fun gmatAppCheckProviderFactory(): AppCheckProviderFactory = DebugAppCheckProviderFactory.getInstance()
