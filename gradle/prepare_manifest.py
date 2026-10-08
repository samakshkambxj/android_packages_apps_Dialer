#!/usr/bin/env python3
"""Prepare the Gradle-only manifest for the .dev staging build.

The staging APK side-installs next to the system Dialer, so every
install-unique name it shares with com.android.dialer must be renamed:
a signature permission cannot be redeclared, and provider authorities must
be unique device-wide. Soong keeps using the original AndroidManifest.xml
untouched.

Caveat: Java code references some of these authorities as literals, so the
corresponding cross-provider paths (file sharing, annotated call log,
lookup, VVM) resolve against the renamed authorities only. Core app flow
and the glass navbar are unaffected.

Usage: prepare_manifest.py <src_manifest> <dst_manifest>
"""

import pathlib
import sys

RENAMES = {
    # Custom signature permission owned by the system Dialer.
    "com.android.dialer.permission.DIALER_ORIGIN":
        "com.android.dialer.dev.permission.DIALER_ORIGIN",
    # Provider authorities (android:authorities values).
    '"com.android.dialer.annotatedcalllog"':
        '"com.android.dialer.dev.annotatedcalllog"',
    '"com.android.dialer.blocking.filterednumberprovider"':
        '"com.android.dialer.dev.blocking.filterednumberprovider"',
    '"com.android.dialer.files"':
        '"com.android.dialer.dev.files"',
    '"com.android.dialer.lookup"':
        '"com.android.dialer.dev.lookup"',
    '"com.android.dialer.phonelookuphistory"':
        '"com.android.dialer.dev.phonelookuphistory"',
    '"com.android.dialer.preferredsimfallback"':
        '"com.android.dialer.dev.preferredsimfallback"',
}


def main():
    src, dst = sys.argv[1], sys.argv[2]
    text = pathlib.Path(src).read_text()
    for old, new in RENAMES.items():
        text = text.replace(old, new)
    out = pathlib.Path(dst)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(text)


if __name__ == "__main__":
    main()
