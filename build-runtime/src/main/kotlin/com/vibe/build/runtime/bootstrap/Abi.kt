package com.vibe.build.runtime.bootstrap

/**
 * Android ABI enumeration recognized by the bootstrap subsystem.
 * Maps `Build.SUPPORTED_ABIS` entries to the two architectures we ship
 * toolchain artifacts for.
 */
enum class Abi(val abiId: String) {
    ARM64("arm64-v8a"),
    ARM32("armeabi-v7a");

    companion object {
        /**
         * Given `Build.SUPPORTED_ABIS` (first entry = preferred), return the first
         * recognized [Abi], or `null` if none are supported by our toolchain.
         */
        fun pickPreferred(supportedAbis: Array<String>): Abi? {
            for (candidate in supportedAbis) {
                val match = entries.firstOrNull { it.abiId == candidate }
                if (match != null) return match
            }
            return null
        }
    }
}
