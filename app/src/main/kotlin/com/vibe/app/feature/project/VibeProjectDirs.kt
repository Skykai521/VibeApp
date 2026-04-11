package com.vibe.app.feature.project

import java.io.File

/**
 * Resolves the `.vibe/` metadata directory layout for a project workspace.
 * `.vibe/` is intentionally a sibling of the `app/` module so the build
 * pipeline never sees it and it does not end up in the APK.
 */
class VibeProjectDirs(
    val projectRoot: File,
) {
    val vibeDir: File = File(projectRoot, ".vibe")
    val snapshotsDir: File = File(vibeDir, "snapshots")
    val snapshotIndexFile: File = File(snapshotsDir, "index.json")
    val memoDir: File = File(vibeDir, "memo")
    val outlineFile: File = File(memoDir, "outline.json")
    val intentFile: File = File(memoDir, "intent.md")
    val pendingRestoreMarker: File = File(snapshotsDir, ".pending_restore")

    fun ensureCreated() {
        snapshotsDir.mkdirs()
        memoDir.mkdirs()
    }

    companion object {
        /**
         * @param workspaceRoot `ProjectWorkspace.rootDir` — i.e. `projects/{id}/app`.
         *   `.vibe/` is placed at its parent directory.
         */
        fun fromWorkspaceRoot(workspaceRoot: File): VibeProjectDirs {
            val parent = workspaceRoot.parentFile
                ?: error("workspaceRoot must have a parent directory: $workspaceRoot")
            return VibeProjectDirs(parent)
        }
    }
}
