package org.lineageos.lib.phone;

import android.content.Context;

import org.lineageos.lib.phone.spn.Item;

import java.util.ArrayList;

/**
 * CI Gradle-build only stub.
 *
 * <p>The Soong/platform build links the real {@code org.lineageos.lib.phone} (sensitive phone
 * numbers database). That library is not published to Maven, so the standalone Gradle build used
 * by CI compiles against this API-compatible stub instead: helplines resolve to an empty list
 * and no number is treated as sensitive. The platform build is unaffected (this source dir is
 * only added to the Gradle source set).
 */
public class SensitivePhoneNumbers {

    private static final SensitivePhoneNumbers INSTANCE = new SensitivePhoneNumbers();

    private SensitivePhoneNumbers() {}

    public static SensitivePhoneNumbers getInstance() {
        return INSTANCE;
    }

    public boolean isSensitiveNumber(Context context, String number, int subId) {
        return false;
    }

    public ArrayList<Item> getSensitivePnInfosForMcc(String mcc) {
        return new ArrayList<>();
    }
}
