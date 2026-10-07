// Gradle-standalone stub for AOSP/Soong builds.
//
// The real implementation ships in the LineageOS tree
// (org.lineageos.lib.phone static lib) and is used by ROM builds.
// This stub only exists so the standalone Gradle (CI staging APK) build
// can compile; it is never packaged into ROM builds because Soong's
// srcs glob (java/**/*.java) does not cover gradle/.

package org.lineageos.lib.phone;

import android.content.Context;
import java.util.ArrayList;
import org.lineageos.lib.phone.spn.Item;

/** No-op stand-in for LineageOS' SensitivePhoneNumbers (standalone Gradle builds only). */
public final class SensitivePhoneNumbers {

    private static final SensitivePhoneNumbers INSTANCE = new SensitivePhoneNumbers();

    private SensitivePhoneNumbers() {}

    public static SensitivePhoneNumbers getInstance() {
        return INSTANCE;
    }

    /** Standalone builds ship no sensitive-number database: never sensitive. */
    public boolean isSensitiveNumber(Context context, String number, int subId) {
        return false;
    }

    /** Standalone builds ship no sensitive-number database: always empty. */
    public ArrayList<Item> getSensitivePnInfosForMcc(String mcc) {
        return new ArrayList<>();
    }
}
