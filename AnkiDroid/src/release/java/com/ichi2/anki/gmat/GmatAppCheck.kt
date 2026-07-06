// SPDX-License-Identifier: GPL-3.0-or-later
package com.ichi2.anki.gmat

import com.google.firebase.appcheck.AppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

/** Release builds: attest with Play Integrity (real device + Google Play). */
fun gmatAppCheckProviderFactory(): AppCheckProviderFactory = PlayIntegrityAppCheckProviderFactory.getInstance()
