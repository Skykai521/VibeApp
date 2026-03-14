package com.tyron.builder.project.util;

import java.util.Objects;
import java.util.function.Supplier;

public final class KeyWithDefaultValue<T> extends Key<T> {

    private final Supplier<T> defaultValueSupplier;

    private KeyWithDefaultValue(String name, Supplier<T> defaultValueSupplier) {
        super(name);
        this.defaultValueSupplier = Objects.requireNonNull(defaultValueSupplier, "defaultValueSupplier");
    }

    public static <T> KeyWithDefaultValue<T> create(String name, Supplier<T> defaultValueSupplier) {
        return new KeyWithDefaultValue<>(name, defaultValueSupplier);
    }

    public static <T> KeyWithDefaultValue<T> create(String name, T defaultValue) {
        return new KeyWithDefaultValue<>(name, () -> defaultValue);
    }

    public T getDefaultValue() {
        return defaultValueSupplier.get();
    }
}
