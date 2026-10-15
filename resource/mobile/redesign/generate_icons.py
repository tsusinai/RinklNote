"""生成页面重设计用细线 SVG 与同源 Android 向量资源。"""
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent
DRAWABLE = ROOT.parents[2] / "app/src/main/res/drawable"
PATHS = {
    "account_wallet": "M4 6l13-3v3 M5 6h14a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2 M21 11h-5v5h5 M17.5 13.5h.1",
    "account_plus": "M12 5v14 M5 12h14 M4 3h16a1 1 0 0 1 1 1v16a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1",
    "budget_target": "M12 3v3 M12 18v3 M3 12h3 M18 12h3 M5.6 5.6l2.1 2.1 M16.3 16.3l2.1 2.1 M18.4 5.6l-2.1 2.1 M7.7 16.3l-2.1 2.1 M12 8a4 4 0 1 0 0 8a4 4 0 0 0-0-8",
    "budget_calendar": "M7 3v4 M17 3v4 M4 6h16v15H4z M4 10h16 M8 14h.1 M12 14h.1 M16 14h.1 M8 18h.1 M12 18h.1",
    "search_spark": "M10.5 4.5a6 6 0 1 0 4 11l5 5 M17 3v4 M14 5h6",
    "search_empty": "M10.5 4.5a6 6 0 1 0 4 11l5 5 M17 3v4 M14 5h6",
    "search_filter": "M4 6h16 M4 12h16 M4 18h16 M9 4v4 M15 10v4 M11 16v4",
    "profile_person": "M12 12a4 4 0 1 0 0-8a4 4 0 0 0 0 8 M4 21a8 8 0 0 1 16 0",
    "profile_sync": "M4 7a8 8 0 0 1 14-2l2 2 M20 17a8 8 0 0 1-14 2l-2-2 M20 5v5h-5 M4 19v-5h5",
    "profile_settings": "M12 8a4 4 0 1 0 0 8a4 4 0 0 0 0-8 M12 2v3 M12 19v3 M2 12h3 M19 12h3 M4.9 4.9l2.1 2.1 M17 17l2.1 2.1 M19.1 4.9L17 7 M7 17l-2.1 2.1",
    "empty_receipt": "M6 3h12v18H6z M9 7h6 M9 11h6 M9 15h3",
    "empty_plus": "M12 4v16 M4 12h16",
    "slider_center": "M6 6h12v12H6z M9 9h6 M9 12h6 M9 15h3",
}

for name, path in PATHS.items():
    svg = f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="{path}" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round"/></svg>\n'
    vector = f'<?xml version="1.0" encoding="utf-8"?>\n<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24"><path android:fillColor="@android:color/transparent" android:strokeColor="#FF555555" android:strokeWidth="1.75" android:strokeLineCap="round" android:strokeLineJoin="round" android:pathData="{path}" /></vector>\n'
    ET.fromstring(svg)
    ET.fromstring(vector)
    (ROOT / f"{name}.svg").write_text(svg, encoding="utf-8")
    (DRAWABLE / f"ic_redesign_{name}.xml").write_text(vector, encoding="utf-8")
print(f"generated {len(PATHS)} SVG / Android vector pairs")
