package com.android.common.io;

import java.io.Closeable;
import java.io.IOException;

/**
 * CI Gradle-build only stub.
 *
 * <p>The Soong/platform build links {@code android-common} (AOSP
 * frameworks helper). That library is not published to Maven, so the
 * standalone Gradle build used by CI compiles against this minimal
 * API-compatible stub instead. The platform build is unaffected (this
 * source dir is only added to the Gradle source set).
 */
public class MoreCloseables {

    private MoreCloseables() {}

    public static void closeQuietly(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException ignored) {
            }
        }
    }
}
