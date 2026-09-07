package com.openaiapi;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;

/**
 * Mutates the JVM's real process environment so child processes (Playwright's
 * driver subprocess) inherit it, without requiring the launcher to set an OS
 * env var. Requires Add-Opens: java.base/java.lang java.base/java.util in the
 * jar manifest (JDK 9+ module encapsulation).
 */
final class EnvUtil {

    private EnvUtil() {}

    @SuppressWarnings("unchecked")
    static void setEnv(String key, String value) {
        try {
            Class<?> pe = Class.forName("java.lang.ProcessEnvironment");
            Field theEnvironmentField = pe.getDeclaredField("theEnvironment");
            theEnvironmentField.setAccessible(true);
            ((Map<String, String>) theEnvironmentField.get(null)).put(key, value);
            Field theCaseInsensitiveEnvironmentField = pe.getDeclaredField("theCaseInsensitiveEnvironment");
            theCaseInsensitiveEnvironmentField.setAccessible(true);
            ((Map<String, String>) theCaseInsensitiveEnvironmentField.get(null)).put(key, value);
        } catch (NoSuchFieldException unixLayout) {
            try {
                for (Class<?> cl : Collections.class.getDeclaredClasses()) {
                    if ("java.util.Collections$UnmodifiableMap".equals(cl.getName())) {
                        Field field = cl.getDeclaredField("m");
                        field.setAccessible(true);
                        ((Map<String, String>) field.get(System.getenv())).put(key, value);
                    }
                }
            } catch (Throwable ignored) {
                // best effort only; worst case Playwright re-downloads unused browsers once
            }
        } catch (Throwable ignored) {
            // best effort only; worst case Playwright re-downloads unused browsers once
        }
    }
}
