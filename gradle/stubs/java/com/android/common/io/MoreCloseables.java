// Gradle-standalone stub for AOSP/Soong builds.
//
// Mirrors the tiny surface of android-common's MoreCloseables used by the
// Dialer (closeQuietly). Soong builds use the real android-common static
// lib; this file is invisible to Soong because its srcs glob
// (java/**/*.java) does not cover gradle/.

package com.android.common.io;

import android.database.Cursor;
import java.io.Closeable;

/** Best-effort close helpers (standalone Gradle builds only). */
public final class MoreCloseables {

    private MoreCloseables() {}

    public static void closeQuietly(Cursor cursor) {
        if (cursor != null) {
            try {
                cursor.close();
            } catch (RuntimeException ignored) {
            }
        }
    }

    public static void closeQuietly(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (RuntimeException rethrown) {
                throw rethrown;
            } catch (Exception ignored) {
            }
        }
    }

    public static void closeQuietly(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (RuntimeException rethrown) {
                throw rethrown;
            } catch (Exception ignored) {
            }
        }
    }
}
