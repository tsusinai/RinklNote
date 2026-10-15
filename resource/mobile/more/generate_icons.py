"""生成侧栏 SVG 与 Android 向量图，二者共享路径。"""
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent
DRAWABLE = ROOT.parents[2] / 'app/src/main/res/drawable'
PATHS = {
    'search': 'M10.5 3.5a7 7 0 1 0 0 14a7 7 0 0 0 0-14 M16 16l4.5 4.5',
    'map': 'M9 5L3 3v15l6 3 6-3 6 3V6l-6-3-6 2 M9 5v16 M15 3v15',
    'import': 'M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9l-6-6 M14 3v6h6 M12 11v7 M9 15l3 3 3-3',
    'currency': 'M5 7a8 8 0 0 1 14-1 M19 3v3h-3 M19 17A8 8 0 0 1 5 18 M5 21v-3h3 M9 8l3 4 3-4 M12 12v6 M9 12h6 M9 15h6',
    'capture': 'M8 3H5a2 2 0 0 0-2 2v3 M16 3h3a2 2 0 0 1 2 2v3 M21 16v3a2 2 0 0 1-2 2h-3 M8 21H5a2 2 0 0 1-2-2v-3 M13 7l-5 6h4l-1 4 5-6h-4z',
    'bot': 'M6 7h12a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2 M12 7V3 M10 3h4 M1 11v5 M23 11v5 M8 11v2 M16 11v2 M9 16h6',
    'settings': 'M9.5 3h5l.5 2.5 2 1.2 2.5-.8 2.5 4.3-2 1.7v2.2l2 1.7-2.5 4.3-2.5-.8-2 1.2-.5 2.5h-5L9 20.5l-2-1.2-2.5.8L2 15.8l2-1.7v-2.2l-2-1.7 2.5-4.3 2.5.8 2-1.2z M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6',
    'info': 'M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18 M12 7v.5 M11 11h1v6 M10 17h4',
    'challenge': 'M7 3h10v5a5 5 0 0 1-10 0V3 M7 5H3v3a4 4 0 0 0 4 4 M17 5h4v3a4 4 0 0 1-4 4 M12 13v7 M8 21h8',
    'close': 'M6 6l12 12 M18 6L6 18',
    'chevron': 'M9 5l7 7-7 7',
}

SVG = ROOT / 'svg'
SVG.mkdir(parents=True, exist_ok=True)
for name, path in PATHS.items():
    svg = f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="{path}" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round"/></svg>\n'
    vector = f'''<?xml version="1.0" encoding="utf-8"?>
<!-- 侧栏图标：修改 resource/mobile/more/generate_icons.py 后重新生成。 -->
<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="@android:color/transparent" android:strokeColor="#FF555555" android:strokeWidth="1.75" android:strokeLineCap="round" android:strokeLineJoin="round" android:pathData="{path}"/>
</vector>
'''
    ET.fromstring(svg)
    ET.fromstring(vector)
    (SVG / f'{name}.svg').write_text(svg, encoding='utf-8')
    (DRAWABLE / f'ic_more_{name}.xml').write_text(vector, encoding='utf-8')
print(f'{len(PATHS)} SVG / Android')
