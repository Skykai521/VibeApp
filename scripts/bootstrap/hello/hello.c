/*
 * Trivial synthetic bootstrap artifact target.
 *
 * Compiled with the Android NDK against Bionic, this proves that an
 * arbitrary ELF binary downloaded to filesDir/usr/opt/<componentId>/
 * can be exec'd by VibeApp's ProcessLauncher. Output is simple enough
 * to regex in instrumented tests.
 */

#include <stdio.h>

int main(void) {
    puts("hello from bootstrap");
    return 0;
}
