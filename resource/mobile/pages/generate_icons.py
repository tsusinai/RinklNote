"""生成页面细线图标的 SVG 与同源 Android 向量。"""
from pathlib import Path
root=Path(__file__).resolve().parents[3]
icons={
'bot':'M9 4h6 M12 2v2 M6 7h12a3 3 0 0 1 3 3v7a3 3 0 0 1-3 3H6a3 3 0 0 1-3-3v-7a3 3 0 0 1 3-3 M8 12h.1 M16 12h.1 M8 16h8 M1 11v4 M23 11v4',
'send':'M4 4l17 8-17 8 3-8z M7 12h14',
'mic':'M9 5a3 3 0 0 1 6 0v7a3 3 0 0 1-6 0z M5 11v1a7 7 0 0 0 14 0v-1 M12 19v3 M8 22h8',
'summary':'M5 3h14v18H5z M8 7h8 M8 11h8 M8 15h4',
'analysis':'M4 20h16 M6 16v-5 M12 16V4 M18 16V8',
'alert':'M12 3L2 21h20z M12 9v5 M12 17h.1',
'plus':'M12 4v16 M4 12h16',
'more':'M5 12h.1 M12 12h.1 M19 12h.1',
'eye':'M2 12s3-7 10-7 10 7 10 7-3 7-10 7-10-7-10-7 M9 12a3 3 0 1 0 6 0a3 3 0 1 0-6 0',
'eye_off':'M3 3l18 18 M9 5.5A11 11 0 0 1 12 5c7 0 10 7 10 7a17 17 0 0 1-4 4 M6 6A17 17 0 0 0 2 12s3 7 10 7a12 12 0 0 0 5-1.5 M10 10a3 3 0 0 0 4 4',
'wallet':'M4 6l13-3v3 M5 6h14a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2 M21 11h-5v5h5 M17.5 13.5h.1',
'close':'M6 6l12 12 M6 18L18 6',
'image':'M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2 M3 16l5-5 4 4 3-3 6 6 M15 7h.1',
'reset':'M3 10a9 9 0 1 1 2 8 M3 4v6h6',
}
import xml.etree.ElementTree as ET
folder=root/'resource/mobile/pages/svg';folder.mkdir(parents=True,exist_ok=True)
for name,path in icons.items():
 svg=f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="{path}" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round"/></svg>\n'
 vector=f'<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24"><path android:fillColor="@android:color/transparent" android:strokeColor="#FF555555" android:strokeWidth="1.75" android:strokeLineCap="round" android:strokeLineJoin="round" android:pathData="{path}"/></vector>\n'
 ET.fromstring(svg);ET.fromstring(vector)
 (folder/f'{name}.svg').write_text(svg,encoding='utf-8')
 (root/f'app/src/main/res/drawable/ic_ui_{name}.xml').write_text(vector,encoding='utf-8')
