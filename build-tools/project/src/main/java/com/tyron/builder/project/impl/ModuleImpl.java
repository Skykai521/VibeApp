package com.tyron.builder.project.impl;

import androidx.annotation.Nullable;

import com.tyron.builder.model.ModuleSettings;
import com.tyron.builder.project.api.FileManager;
import com.tyron.builder.project.api.Module;
import com.tyron.builder.project.util.Key;
import com.tyron.builder.project.util.KeyWithDefaultValue;
import com.tyron.common.util.Cache;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HashMap;
import java.util.Map;

public class ModuleImpl implements Module {

    @NotNull
    private final ConcurrentHashMap<Key<?>, Object> userData = new ConcurrentHashMap<>();
    private final File mRoot;
    private ModuleSettings myModuleSettings;
    private FileManager mFileManager;

    public ModuleImpl(File root) {
        mRoot = root;
        mFileManager = new FileManagerImpl(root);
    }

    @Override
    public void open() throws IOException {
        myModuleSettings = new ModuleSettings(new File(getRootFile(), "app_config.json"));
    }

    @Override
    public void clear() {

    }

    @Override
    public void index() {

    }

    @Override
    public File getBuildDirectory() {
        File custom = getPathSetting("build_directory");
        if (custom.exists()) {
            return custom;
        }
        return new File(getRootFile(), "build");
    }

    @Override
    public ModuleSettings getSettings() {
        return myModuleSettings;
    }

    @Override
    public FileManager getFileManager() {
        return mFileManager;
    }

    @Override
    public File getRootFile() {
        return mRoot;
    }

    @Nullable
    @Override
    public <T> T getUserData(@NotNull Key<T> key) {
        @SuppressWarnings("unchecked")
        T t = (T) userData.get(key);
        if (t == null && key instanceof KeyWithDefaultValue) {
            t = ((KeyWithDefaultValue<T>) key).getDefaultValue();
            @SuppressWarnings("unchecked")
            T existing = (T) userData.putIfAbsent(key, t);
            if (existing != null) {
                return existing;
            }
        }
        return t;
    }

    @Override
    public <T> void putUserData(@NotNull Key<T> key, @Nullable T value) {
        if (value == null) {
            userData.remove(key);
        } else {
            userData.put(key, value);
        }
    }

    @NotNull
    @Override
    public <T> T putUserDataIfAbsent(@NotNull Key<T> key, @NotNull T value) {
        @SuppressWarnings("unchecked")
        T existing = (T) userData.putIfAbsent(key, value);
        return existing != null ? existing : value;
    }

    @Override
    public <T> boolean replace(@NotNull Key<T> key, @Nullable T oldValue, @Nullable T newValue) {
        if (newValue == null) {
            return userData.remove(key, oldValue);
        }
        return userData.replace(key, oldValue, newValue);
    }

    protected File getPathSetting(String key) {
        String path = getSettings().getString(key, "");
        return new File(path);
    }

    @Override
    public int hashCode() {
        return mRoot.hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ModuleImpl)) return false;
        ModuleImpl project = (ModuleImpl) o;
        return mRoot.equals(project.mRoot);
    }

    private final Map<CacheKey<?, ?>, Cache<?, ?>> mCacheMap = new HashMap<>();

    @Override
    public <K, V> Cache<K, V> getCache(CacheKey<K, V> key, Cache<K, V> defaultValue) {
        Object o = mCacheMap.get(key);
        if (o == null) {
            put(key, defaultValue);
            return defaultValue;
        }
        //noinspection unchecked
        return (Cache<K, V>) o;
    }

    public <K, V> void removeCache(CacheKey<K, V> key) {
        mCacheMap.remove(key);
    }

    @Override
    public <K, V> void put(CacheKey<K, V> key, Cache<K, V> value) {
        mCacheMap.put(key, value);
    }
}
