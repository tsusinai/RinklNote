"""生成计划页细线 SVG 与同源 Android 向量图。"""
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent
DRAWABLE = ROOT.parents[2] / 'app/src/main/res/drawable'
PATHS = {
    'wallet': 'M4 6l13-3v3 M5 6h14a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2 M21 11h-5v5h5 M17.5 13.5h.1',
    'edit': 'M14 5l5 5 M4 20l4-1 12-12a2.8 2.8 0 0 0-4-4L4 15z M12 20h8',
    'calendar': 'M7 3v4 M17 3v4 M5 5h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2 M3 10h18 M7 14h3 M14 14h3 M7 17h3',
    'settings': 'M4 6h16 M4 12h16 M4 18h16 M8 4v4 M16 10v4 M10 16v4',
    'chevron': 'M9 5l7 7-7 7',
}
SVG = ROOT / 'svg'
SVG.mkdir(parents=True, exist_ok=True)
for name, path in PATHS.items():
    svg = f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="{path}" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round"/></svg>\n'
    vector = f'''<?xml version="1.0" encoding="utf-8"?>
<!-- 修改 resource/mobile/plan/generate_icons.py 后重新生成。 -->
<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="@android:color/transparent" android:strokeColor="#FF555555" android:strokeWidth="1.75" android:strokeLineCap="round" android:strokeLineJoin="round" android:pathData="{path}"/>
</vector>
'''
    ET.fromstring(svg); ET.fromstring(vector)
    (SVG / f'{name}.svg').write_text(svg, encoding='utf-8')
    (DRAWABLE / f'ic_plan_{name}.xml').write_text(vector, encoding='utf-8')
print(f'{len(PATHS)} SVG / Android')
