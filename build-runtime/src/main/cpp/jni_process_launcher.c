/*
 * JNI bridge for process_launcher.c.
 *
 * Marshals Kotlin String + String[] to C-style char** and back. Returns
 * results as an int[4] = { pid, stdoutFd, stderrFd, stdinFd }. On
 * failure, pid == -1 and the other three entries are -1.
 *
 * All allocated C strings are freed before returning. The caller
 * (Kotlin) is responsible for closing the fds via ParcelFileDescriptor.
 */

#include <jni.h>
#include <stdlib.h>
#include <string.h>

#include "process_launcher.h"

/*
 * Convert a Java String[] to a malloc'd NULL-terminated char**.
 * Returns NULL on allocation failure. Caller must free via free_c_strings().
 */
static char** jstring_array_to_c_strings(JNIEnv* env, jobjectArray array) {
    jsize len = (*env)->GetArrayLength(env, array);
    char** result = (char**)calloc((size_t)len + 1, sizeof(char*));
    if (result == NULL) {
        return NULL;
    }

    for (jsize i = 0; i < len; i++) {
        jstring js = (jstring)(*env)->GetObjectArrayElement(env, array, i);
        const char* chars = (*env)->GetStringUTFChars(env, js, NULL);
        if (chars != NULL) {
            result[i] = strdup(chars);
            (*env)->ReleaseStringUTFChars(env, js, chars);
        }
        (*env)->DeleteLocalRef(env, js);
    }
    result[len] = NULL;
    return result;
}

static void free_c_strings(char** arr) {
    if (arr == NULL) return;
    for (char** p = arr; *p != NULL; p++) {
        free(*p);
    }
    free(arr);
}

JNIEXPORT jintArray JNICALL
Java_com_vibe_build_runtime_process_NativeProcessBridge_nativeLaunch(
    JNIEnv* env,
    jclass clazz,
    jstring jExecutable,
    jobjectArray jArgv,
    jobjectArray jEnvp,
    jstring jCwd
) {
    (void)clazz;

    const char* executable = (*env)->GetStringUTFChars(env, jExecutable, NULL);
    char** argv = jstring_array_to_c_strings(env, jArgv);
    char** envp = jstring_array_to_c_strings(env, jEnvp);
    const char* cwd = (jCwd != NULL) ? (*env)->GetStringUTFChars(env, jCwd, NULL) : NULL;

    int stdin_fd = -1, stdout_fd = -1, stderr_fd = -1;
    pid_t pid = pl_launch(
        executable,
        (const char* const*)argv,
        (const char* const*)envp,
        cwd,
        &stdout_fd,
        &stderr_fd,
        &stdin_fd
    );

    /* Release JNI strings + temp C arrays. */
    (*env)->ReleaseStringUTFChars(env, jExecutable, executable);
    if (cwd != NULL) {
        (*env)->ReleaseStringUTFChars(env, jCwd, cwd);
    }
    free_c_strings(argv);
    free_c_strings(envp);

    jintArray result = (*env)->NewIntArray(env, 4);
    if (result == NULL) {
        /* Clean up fds if allocation failed */
        if (pid > 0) {
            pl_signal(pid, 9 /* SIGKILL */);
            pl_wait(pid);
        }
        return NULL;
    }
    jint values[4] = { (jint)pid, (jint)stdout_fd, (jint)stderr_fd, (jint)stdin_fd };
    (*env)->SetIntArrayRegion(env, result, 0, 4, values);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_vibe_build_runtime_process_NativeProcessBridge_nativeSignal(
    JNIEnv* env,
    jclass clazz,
    jint pid,
    jint signum
) {
    (void)env;
    (void)clazz;
    return (jint)pl_signal((pid_t)pid, (int)signum);
}

JNIEXPORT jint JNICALL
Java_com_vibe_build_runtime_process_NativeProcessBridge_nativeWaitFor(
    JNIEnv* env,
    jclass clazz,
    jint pid
) {
    (void)env;
    (void)clazz;
    return (jint)pl_wait((pid_t)pid);
}
