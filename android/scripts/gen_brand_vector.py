"""Generate adaptive icon foreground vector from repo-root bearis-logo.svg."""
import pathlib
import re

root = pathlib.Path(__file__).resolve().parents[2]
svg_path = root / "bearis-logo.svg"
out_path = root / "android" / "app" / "src" / "main" / "res" / "drawable" / "ic_brand_logo.xml"

svg = svg_path.read_text(encoding="utf-8")
paths = re.findall(r'<path\s+d="([^"]+)"', svg, re.DOTALL)
paths = [re.sub(r"\s+", " ", p.strip()) for p in paths]
if len(paths) != 5:
    raise SystemExit(f"expected 5 paths, got {len(paths)}")

view_box_match = re.search(r'viewBox="([^"]+)"', svg)
if not view_box_match:
    raise SystemExit("missing viewBox")
vb = [float(x) for x in view_box_match.group(1).split()]
if len(vb) != 4:
    raise SystemExit(f"unexpected viewBox: {view_box_match.group(1)}")
vb_x, vb_y, vb_w, vb_h = vb

# Preserve the root <g transform="..."> from the source SVG when present.
transform_match = re.search(r"<g[^>]*transform=\"([^\"]+)\"", svg, re.DOTALL)
source_transform = transform_match.group(1).strip() if transform_match else ""
translate_match = re.search(r"translate\(\s*([-\d.]+)[,\s]+([-\d.]+)\s*\)", source_transform)
scale_match = re.search(r"scale\(\s*([-\d.]+)[,\s]+([-\d.]+)\s*\)", source_transform)
src_tx = float(translate_match.group(1)) if translate_match else 0.0
src_ty = float(translate_match.group(2)) if translate_match else 0.0
src_sx = float(scale_match.group(1)) if scale_match else 1.0
src_sy = float(scale_match.group(2)) if scale_match else 1.0

path_elems = "\n            ".join(
    f'<path android:fillColor="#FFFFFFFF" android:fillType="nonZero" android:pathData="{p}"/>'
    for p in paths
)

# Fit source viewport into 108x108 adaptive safe area.
s = min(108 / vb_w, 108 / vb_h)
tx = (108 - vb_w * s) / 2
ty = (108 - vb_h * s) / 2

xml = f'''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <group
        android:translateX="{tx:.6f}"
        android:translateY="{ty:.6f}"
        android:scaleX="{s:.10f}"
        android:scaleY="{s:.10f}">
        <group
            android:translateX="{src_tx:.6f}"
            android:translateY="{src_ty:.6f}"
            android:scaleX="{src_sx:.10f}"
            android:scaleY="{src_sy:.10f}">
            {path_elems}
        </group>
    </group>
</vector>
'''
out_path.write_text(xml, encoding="utf-8")
print("wrote", out_path)
