"""生成已选定的「收支页签」品牌资源，保留可编辑 SVG 原稿。"""
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
OUT = Path(__file__).parent / 'brand'
OUT.mkdir(exist_ok=True)
PATH = 'M64 32h128a32 32 0 0 1 32 32v40H96v16H32V64a32 32 0 0 1 32-32z M160 136h64v56a32 32 0 0 1-32 32H64a32 32 0 0 1-32-32v-40h128z'

def svg(name, fill, background=None):
    tile = f'<rect width="256" height="256" rx="56" fill="{background}"/>' if background else ''
    transform = ' transform="translate(25.6 25.6) scale(.8)"' if background else ''
    source = f'''<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 256 256" role="img" aria-labelledby="title">
<title id="title">记一笔 · 收支页签</title>{tile}<path fill="{fill}" d="{PATH}"{transform}/></svg>
'''
    ET.fromstring(source)
    (OUT / name).write_text(source, encoding='utf-8')

for name, color in [('symbol-blue.svg', '#5AA8E8'), ('symbol-black.svg', '#111111'), ('symbol-white.svg', '#FFFFFF')]:
    svg(name, color)
svg('app-icon.svg', '#FFFFFF', '#7EC1FC')

drawable = ROOT / 'app/src/main/res/drawable'
for name, viewport, body in [
    ('ic_brand', '256', f'<path android:fillColor="#FF5AA8E8" android:pathData="{PATH}"/>'),
    ('ic_brand_mono', '256', f'<path android:fillColor="#FF000000" android:pathData="{PATH}"/>'),
    ('ic_launcher_foreground', '108', f'<group android:scaleX="0.3" android:scaleY="0.3" android:translateX="15.6" android:translateY="15.6"><path android:fillColor="#FFFFFFFF" android:pathData="{PATH}"/></group>'),
    ('ic_launcher_monochrome', '108', f'<group android:scaleX="0.3" android:scaleY="0.3" android:translateX="15.6" android:translateY="15.6"><path android:fillColor="#FF000000" android:pathData="{PATH}"/></group>'),
    ('ic_launcher_background', '108', '<path android:fillColor="#FF7EC1FC" android:pathData="M0 0h108v108H0z"/>'),
]:
    size = '24' if viewport == '256' else viewport
    xml = f'''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="{size}dp" android:height="{size}dp" android:viewportWidth="{viewport}" android:viewportHeight="{viewport}">{body}</vector>
'''
    ET.fromstring(xml)
    (drawable / f'{name}.xml').write_text(xml, encoding='utf-8')

for folder, mono in [('mipmap-anydpi-v26', False), ('mipmap-anydpi-v33', True)]:
    dest = ROOT / 'app/src/main/res' / folder
    dest.mkdir(exist_ok=True)
    for name in ['ic_launcher', 'ic_launcher_round']:
        xml = '''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background"/>
    <foreground android:drawable="@drawable/ic_launcher_foreground"/>
'''
        if mono:
            xml += '    <monochrome android:drawable="@drawable/ic_launcher_monochrome"/>\n'
        xml += '</adaptive-icon>\n'
        (dest / f'{name}.xml').write_text(xml, encoding='utf-8')

print('已生成收支页签 SVG、应用标识和自适应图标')

# 可选的兼容位图导出；源图仍是 SVG，Android 28+ 使用上面的自适应向量。
if '--raster' in __import__('sys').argv:
    import cairosvg
    from PIL import Image
    from io import BytesIO
    for density, size in [('mdpi', 48), ('hdpi', 72), ('xhdpi', 96), ('xxhdpi', 144), ('xxxhdpi', 192)]:
        dest = ROOT / 'app/src/main/res' / f'mipmap-{density}'
        for name, tile in [('ic_launcher', '<rect width="256" height="256" rx="56" fill="#7EC1FC"/>'),
                           ('ic_launcher_round', '<circle cx="128" cy="128" r="128" fill="#7EC1FC"/>')]:
            source = f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 256 256">{tile}<path fill="#FFFFFF" d="{PATH}" transform="translate(25.6 25.6) scale(.8)"/></svg>'
            png = cairosvg.svg2png(bytestring=source.encode(), output_width=size, output_height=size)
            Image.open(BytesIO(png)).save(dest / f'{name}.webp', lossless=True)
    print('已替换 5 档启动图标及圆形兼容资源')
