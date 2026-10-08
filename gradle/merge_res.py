#!/usr/bin/env python3
"""Merge Dialer resource dirs with Soong overlay semantics.

Soong merges Android.bp resource_dirs with later dirs silently overriding
earlier ones per resource key. AGP/AAPT2 instead fails the build on
duplicate resources, so the standalone Gradle CI build pre-merges every
res dir into one tree: for values/* XML the winning element per
(type, name) is kept (last dir wins), every other file is copied last-wins
by relative path.

Usage: merge_res.py <out_dir> <res_dir> [<res_dir> ...]
"""

import os
import re
import shutil
import sys
import xml.etree.ElementTree as ET

XMLNS_RE = re.compile(r'xmlns:([A-Za-z_][\w.-]*)\s*=\s*"([^"]+)"')

# Gradle's AAPT2 cannot link framework-private resources, which the tree
# uses for font families. The literals match AOSP's values for these
# config strings, so the merged output resolves identically.
FONT_FALLBACKS = {
    "@*android:string/config_bodyFontFamily": "sans-serif",
    "@*android:string/config_headlineFontFamilyMedium": "sans-serif-medium",
}


def with_font_fallbacks(text):
    for old, new in FONT_FALLBACKS.items():
        text = text.replace(old, new)
    return text


def resource_key(elem):
    """(type, name) identifying a <resources> child, or None to always keep."""
    if elem.tag == "item":
        return (elem.get("type"), elem.get("name"))
    if elem.tag == "public":
        return ("public:" + (elem.get("type") or ""), elem.get("name"))
    if elem.tag == "declare-styleable":
        return ("styleable", elem.get("name"))
    name = elem.get("name")
    if name is None:
        return None
    return (elem.tag, name)


def main():
    out_dir, roots = sys.argv[1], sys.argv[2:]
    # qualifier dir -> key -> element (document order of first sight kept)
    values = {}
    order = {}
    namespaces = {}
    nameless = {}

    for root in roots:
        if not os.path.isdir(root):
            continue
        for dirpath, _, filenames in os.walk(root):
            rel_dir = os.path.relpath(dirpath, root)
            for filename in sorted(filenames):
                src = os.path.join(dirpath, filename)
                rel = os.path.normpath(os.path.join(rel_dir, filename))
                if rel_dir.startswith("values") and filename.endswith(".xml"):
                    try:
                        with open(src, "r", encoding="utf-8") as f:
                            text = with_font_fallbacks(f.read())
                    except OSError:
                        continue
                    for prefix, uri in XMLNS_RE.findall(text):
                        namespaces.setdefault(prefix, uri)
                    try:
                        tree_root = ET.fromstring(text)
                    except ET.ParseError:
                        continue
                    if tree_root.tag != "resources":
                        continue
                    bucket = values.setdefault(rel_dir, {})
                    if rel_dir not in order:
                        order[rel_dir] = []
                        nameless[rel_dir] = []
                    for elem in list(tree_root):
                        if not isinstance(elem.tag, str):
                            continue
                        key = resource_key(elem)
                        if key[0] is None or key[1] is None:
                            nameless[rel_dir].append(elem)
                        else:
                            if key not in bucket:
                                order[rel_dir].append(key)
                            bucket[key] = elem
                else:
                    # Last dir wins by relative path.
                    dst = os.path.join(out_dir, rel)
                    os.makedirs(os.path.dirname(dst), exist_ok=True)
                    if filename.endswith(".xml"):
                        with open(src, "r", encoding="utf-8") as f:
                            text = with_font_fallbacks(f.read())
                        with open(dst, "w", encoding="utf-8") as f:
                            f.write(text)
                    else:
                        shutil.copy2(src, dst)

    for prefix, uri in namespaces.items():
        try:
            ET.register_namespace(prefix, uri)
        except ValueError:
            pass
    for rel_dir, bucket in values.items():
        dst_dir = os.path.join(out_dir, rel_dir)
        os.makedirs(dst_dir, exist_ok=True)
        # AAPT treats a nested <attr> carrying a format (or enum/flag
        # children) as a *definition*, so the same attr defined by two
        # styleables (or top-level and nested) fails the link as a
        # duplicate. Later duplicates are reduced to bare references.
        defined_attrs = {
            key[1]
            for key in order[rel_dir]
            if key[0] == "attr"
        }
        out_root = ET.Element("resources")
        for key in order[rel_dir]:
            elem = bucket[key]
            if elem.tag == "declare-styleable":
                for child in list(elem):
                    if (
                        child.tag == "attr"
                        and child.get("name")
                        and (child.get("format") or len(list(child)))
                    ):
                        if child.get("name") in defined_attrs:
                            name = child.get("name")
                            child.attrib.clear()
                            for grand in list(child):
                                child.remove(grand)
                            child.set("name", name)
                        else:
                            defined_attrs.add(child.get("name"))
            out_root.append(elem)
        for elem in nameless[rel_dir]:
            out_root.append(elem)
        tree = ET.ElementTree(out_root)
        ET.indent(tree, space="    ")
        with open(os.path.join(dst_dir, "merged_resources.xml"), "wb") as f:
            tree.write(f, encoding="utf-8", xml_declaration=True)


if __name__ == "__main__":
    main()
