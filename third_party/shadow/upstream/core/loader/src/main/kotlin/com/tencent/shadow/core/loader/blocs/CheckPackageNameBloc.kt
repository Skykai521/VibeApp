/*
 * Tencent is pleased to support the open source community by making Tencent Shadow available.
 * Copyright (C) 2019 THL A29 Limited, a Tencent company.  All rights reserved.
 *
 * Licensed under the BSD 3-Clause License (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *     https://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.tencent.shadow.core.loader.blocs

import android.content.Context
import android.util.Log
import com.tencent.shadow.core.loader.exceptions.ParsePluginApkException
import com.tencent.shadow.core.runtime.PluginManifest

object CheckPackageNameBloc {
    @Throws(ParsePluginApkException::class)
    fun check(
        pluginManifest: PluginManifest,
        hostAppContext: Context
    ) {
        // VibeApp deliberately gives every generated project its own
        // applicationId (e.g. `com.vibe.generated.p202604193`) so the
        // plugin APK is also installable standalone via the system
        // installer. In plugin-runtime mode upstream Shadow's strict
        // equality check forbids that — the original reasoning (kept
        // below for context) is irrelevant for our use case:
        //
        //   1. Resources.getIdentifier(name, …) returns wrong IDs because
        //      Context.getPackageName resolves to the host's package, not
        //      the plugin's. Compose-only plugins reference resources via
        //      compile-time R constants, never by string lookup, so this
        //      doesn't affect us.
        //   2. OEM-modified Android may pull packageName off the plugin's
        //      Context for permission/system queries. Generated projects
        //      request no permissions, so the worst case is a permission
        //      query against `com.vibe.app` (the host) instead of the
        //      plugin's id — still safe, since the host already declares
        //      whatever it needs.
        //
        // Log a warning so resource-lookup-by-name regressions are easier
        // to spot, but don't fail the load.
        if (pluginManifest.applicationPackageName != hostAppContext.packageName) {
            Log.w(
                "CheckPackageNameBloc",
                "plugin/host package mismatch tolerated " +
                    "(host=${hostAppContext.packageName}, " +
                    "plugin=${pluginManifest.applicationPackageName}). " +
                    "Avoid Resources.getIdentifier(name, …) in plugin code.",
            )
            // Suppress unused-import warning when the throw is removed.
            if (false) throw ParsePluginApkException("unreachable")
        }
    }
}
