#!/usr/bin/env python3
"""Convert Bootstrap Icons SVGs to Android VectorDrawables."""
import re
import pathlib

SRC = pathlib.Path("/tmp/bi/icons-1.11.3/icons")
DST = pathlib.Path(__file__).resolve().parents[1] / "res" / "drawable"
DST.mkdir(parents=True, exist_ok=True)

MAPPING = {
    "pencil": "ic_pencil",
    "eraser": "ic_eraser",
    "bounding-box": "ic_lasso",
    "arrow-counterclockwise": "ic_undo",
    "brush": "ic_ink",
    "code-square": "ic_code",
    "image": "ic_image",
    "clipboard": "ic_copy",
    "x-lg": "ic_clear",
    "trash": "ic_delete",
    "folder2-open": "ic_open",
    "chat-dots": "ic_chat",
    "chevron-right": "ic_chevron_right",
    "chevron-left": "ic_chevron_left",
}

for name, out in MAPPING.items():
    svg = (SRC / f"{name}.svg").read_text()
    paths = re.findall(r'<path[^>]*\sd="([^"]+)"', svg)
    if not paths:
        raise SystemExit(f"no path in {name}")
    vb = re.search(r'viewBox="([^"]+)"', svg)
    parts = (vb.group(1) if vb else "0 0 16 16").split()
    w, h = parts[2], parts[3]
    elems = "\n".join(
        f'    <path\n'
        f'        android:fillColor="#FFFFFFFF"\n'
        f'        android:pathData="{d}" />'
        for d in paths
    )
    xml = f'''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="{w}"
    android:viewportHeight="{h}">
{elems}
</vector>
'''
    (DST / f"{out}.xml").write_text(xml)
    print("wrote", out)
