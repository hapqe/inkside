#!/usr/bin/env python3
"""Convert Material Symbols SVGs to Android VectorDrawables."""
import re
import pathlib
import urllib.request

SRC = pathlib.Path("/tmp/mi")
DST = pathlib.Path(__file__).resolve().parents[1] / "res" / "drawable"
DST.mkdir(parents=True, exist_ok=True)
SRC.mkdir(parents=True, exist_ok=True)

BASE = "https://fonts.gstatic.com/s/i/short-term/release/materialsymbolsoutlined"

NAMES = [
    "edit", "ink_eraser", "lasso_select", "gesture", "undo", "brush", "code",
    "image", "content_copy", "close", "delete", "folder_open", "chat",
    "chevron_left", "chevron_right", "send", "attach_file",
]

for name in NAMES:
    dest = SRC / f"{name}.svg"
    if dest.exists() and dest.stat().st_size > 40:
        continue
    url = f"{BASE}/{name}/default/24px.svg"
    try:
        urllib.request.urlretrieve(url, dest)
        print("dl", name)
    except Exception as e:
        print("fail", name, e)

MAPPING = {
    "edit": "ic_pencil",
    "ink_eraser": "ic_eraser",
    "undo": "ic_undo",
    "brush": "ic_ink",
    "code": "ic_code",
    "image": "ic_image",
    "content_copy": "ic_copy",
    "close": "ic_clear",
    "delete": "ic_delete",
    "folder_open": "ic_open",
    "chat": "ic_chat",
    "chevron_left": "ic_chevron_left",
    "chevron_right": "ic_chevron_right",
    "send": "ic_send",
    "attach_file": "ic_attach",
}

lasso_src = "lasso_select" if (SRC / "lasso_select.svg").exists() and (SRC / "lasso_select.svg").stat().st_size > 40 else "gesture"
MAPPING[lasso_src] = "ic_lasso"

for name, out in MAPPING.items():
    svg = (SRC / f"{name}.svg").read_text()
    paths = re.findall(r'<path[^>]*\sd="([^"]+)"', svg)
    if not paths:
        raise SystemExit(f"no path in {name}")
    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        '    android:width="24dp"',
        '    android:height="24dp"',
        '    android:viewportWidth="960"',
        '    android:viewportHeight="960"',
        '    android:tint="#FFFFFFFF">',
        '    <group android:translateY="960">',
    ]
    for d in paths:
        lines.append(f'        <path android:fillColor="#FFFFFFFF" android:pathData="{d}" />')
    lines.append("    </group>")
    lines.append("</vector>")
    lines.append("")
    (DST / f"{out}.xml").write_text("\n".join(lines))
    print("wrote", out, "from", name)
