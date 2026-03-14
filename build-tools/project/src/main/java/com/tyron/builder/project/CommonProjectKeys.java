package com.tyron.builder.project;

import com.tyron.builder.compiler.manifest.xml.ManifestData;
import com.tyron.builder.project.util.Key;
import com.tyron.builder.project.util.KeyWithDefaultValue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class CommonProjectKeys {

    public static final Key<File> ROOT_DIR_KEY = Key.create("rootDir");
    public static final Key<File> ASSETS_DIR_KEY = Key.create("assetsDir");
    public static final Key<File> NATIVE_LIBS_DIR_KEY = Key.create("nativeLibsDir");
    public static final Key<File> MANIFEST_FILE_KEY = Key.create("manifestFile");
    public static final Key<File> CONFIG_FILE_KEY = Key.create("configFile");
    public static final Key<File> JAVA_DIR_KEY = Key.create("javaDir");
    public static final Key<ManifestData> MANIFEST_DATA_KEY = Key.create("manifestData");

    public static final Key<List<File>> JAVA_FILES_KEY =
            KeyWithDefaultValue.create("javaFiles", ArrayList::new);
    public static final Key<List<File>> KOTLIN_FILES_KEY =
            KeyWithDefaultValue.create("kotlinFiles", ArrayList::new);
}
