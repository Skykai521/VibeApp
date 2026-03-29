package com.tyron.builder;

import android.content.Context;

import androidx.annotation.NonNull;

import com.tyron.common.util.Decompress;

import java.io.File;

public class BuildModule {

    private static Context sApplicationContext;
    private static File sAndroidJar;
    private static File sLambdaStubs;
    private static File sAndroidxClassesJar;
    private static File sAndroidxResCompiledDir;
    private static File sShadowRuntimeJar;

    public static void initialize(Context applicationContext) {
            sApplicationContext = applicationContext.getApplicationContext();
    }

    public static Context getContext() {
        return sApplicationContext;
    }

    public static File getAndroidJar() {
        if (sAndroidJar == null) {
            Context context = BuildModule.getContext();
            if (context == null) {
                return null;
            }

            sAndroidJar = new File(context
                    .getFilesDir(), "rt.jar");
            if (!sAndroidJar.exists()) {
                Decompress.unzipFromAssets(BuildModule.getContext(),
                        "rt.zip",
                        sAndroidJar.getParentFile().getAbsolutePath());
            }
        }

        return sAndroidJar;
    }

    public static File getLambdaStubs() {
        if (sLambdaStubs == null) {
            sLambdaStubs = new File(BuildModule.getContext().getFilesDir(), "core-lambda-stubs.jar");

            if (!sLambdaStubs.exists()) {
                Decompress.unzipFromAssets(BuildModule.getContext(), "lambda-stubs.zip", sLambdaStubs.getParentFile().getAbsolutePath());
            }
        }
        return sLambdaStubs;
    }

    public static File getAndroidxClassesJar() {
        if (sAndroidxClassesJar == null) {
            sAndroidxClassesJar = new File(BuildModule.getContext().getFilesDir(), "androidx-classes.jar");

            if (!sAndroidxClassesJar.exists()) {
                Decompress.unzipFromAssets(BuildModule.getContext(), "androidx-classes.jar.zip", sAndroidxClassesJar.getParentFile().getAbsolutePath());
            }
        }
        return sAndroidxClassesJar;
    }

    public static File getAndroidxResCompiledDir() {
        if (sAndroidxResCompiledDir == null) {
            sAndroidxResCompiledDir = new File(BuildModule.getContext().getFilesDir(), "androidx-res-compiled");

            if (!sAndroidxResCompiledDir.exists()) {
                Decompress.unzipFromAssets(BuildModule.getContext(), "androidx-res-compiled.zip", sAndroidxResCompiledDir.getAbsolutePath());
            }
        }
        return sAndroidxResCompiledDir;
    }

    public static File getShadowRuntimeJar() {
        if (sShadowRuntimeJar == null) {
            Context context = BuildModule.getContext();
            if (context == null) return null;
            sShadowRuntimeJar = new File(context.getFilesDir(), "shadow-runtime.jar");
            if (!sShadowRuntimeJar.exists()) {
                try {
                    java.io.InputStream is = context.getAssets().open("shadow-runtime.jar");
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(sShadowRuntimeJar);
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = is.read(buffer)) != -1) {
                        fos.write(buffer, 0, read);
                    }
                    fos.close();
                    is.close();
                } catch (java.io.IOException e) {
                    e.printStackTrace();
                    return null;
                }
            }
        }
        return sShadowRuntimeJar;
    }

    public static void setAndroidJar(@NonNull File jar) {
        sAndroidJar = jar;
    }

    public static void setLambdaStubs(File file) {
        sLambdaStubs = file;
    }
}
