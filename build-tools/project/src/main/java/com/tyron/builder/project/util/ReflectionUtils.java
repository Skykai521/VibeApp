package com.tyron.builder.project.util;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class ReflectionUtils {

    private ReflectionUtils() {
    }

    public static Method getDeclaredMethod(Class<?> clazz, String name) throws NoSuchMethodException {
        Method method = clazz.getDeclaredMethod(name);
        method.setAccessible(true);
        return method;
    }

    @SuppressWarnings("unchecked")
    public static <T> T getStaticFieldValue(Class<?> clazz, Class<T> fieldType, String name)
            throws NoSuchFieldException, IllegalAccessException {
        Field field = clazz.getDeclaredField(name);
        field.setAccessible(true);
        return (T) fieldType.cast(field.get(null));
    }
}
