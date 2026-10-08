package org.lineageos.lib.phone.spn;

/**
 * CI Gradle-build only stub.
 *
 * <p>The Soong/platform build links the real {@code org.lineageos.lib.phone} (sensitive phone
 * numbers database). That library is not published to Maven, so the standalone Gradle build used
 * by CI compiles against this API-compatible stub instead: helplines resolve to an empty list
 * and no number is treated as sensitive. The platform build is unaffected (this source dir is
 * only added to the Gradle source set).
 */
public class Item {

    public String getName() {
        return null;
    }

    public String getNumber() {
        return null;
    }

    public String getCategories() {
        return null;
    }

    public String getLanguages() {
        return null;
    }

    public String getOrganization() {
        return null;
    }

    public String getWebsite() {
        return null;
    }
}
