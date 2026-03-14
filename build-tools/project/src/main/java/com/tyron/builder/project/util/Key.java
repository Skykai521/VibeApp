package com.tyron.builder.project.util;

import java.util.Objects;

public class Key<T> {

    private final String name;

    protected Key(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    public static <T> Key<T> create(String name) {
        return new Key<>(name);
    }

    public final String getName() {
        return name;
    }

    @Override
    public final String toString() {
        return name;
    }
}
