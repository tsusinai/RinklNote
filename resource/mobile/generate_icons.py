"""以同一份路径生成移动端 SVG 原稿和 Android VectorDrawable。"""
from pathlib import Path
from xml.etree import ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
OUT = Path(__file__).parent / 'icons'
DRAWABLE = ROOT / 'app/src/main/res/drawable'
# 24 × 24 网格，1.75 线宽，圆角端点；避开位图、滤镜和字体依赖。
ICONS = {
    'register': ('计划', 'M6 3h9a2 2 0 0 1 2 2v6 M11 21H6a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2 M8 7h5 M8 11h5 M14 20l6-6 2 2-6 6h-2z'),
    'wallet': ('记账', 'M20 8V6a2 2 0 0 0-2-2H6a3 3 0 0 0 0 6h14v10H6a3 3 0 0 1-3-3V7 M20 12h-5v5h5 M17 14.5h.01'),
    'finance': ('资产', 'M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18 M8.5 7l3.5 4 3.5-4 M8 12h8 M8 15h8 M12 11v7'),
    'profile': ('我的', 'M12 3a3.5 3.5 0 1 0 0 7 3.5 3.5 0 0 0 0-7 M5 21v-2a7 7 0 0 1 14 0v2'),
    'more_menu': ('更多', 'M3 4a1 1 0 0 1 1-1h5a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1z M14 4a1 1 0 0 1 1-1h5a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1h-5a1 1 0 0 1-1-1z M3 15a1 1 0 0 1 1-1h5a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1z M14 15a1 1 0 0 1 1-1h5a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1h-5a1 1 0 0 1-1-1z'),
    'add_bill': ('记一笔', 'M9 21H6a3 3 0 0 1-3-3V6a3 3 0 0 1 3-3h12a3 3 0 0 1 3 3v4 M7 8h10 M7 12h5 M16 14v8 M12 18h8'),
    'backspace': ('退格', 'M9 5h11a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H9l-7-7z M12 9l6 6 M18 9l-6 6'),
    'edit': ('编辑', 'M14 5l5 5 M4 16L17 3a2.12 2.12 0 0 1 3 3L7 19l-4 1z'),
    'mic': ('语音', 'M12 3a3 3 0 0 0-3 3v6a3 3 0 0 0 6 0V6a3 3 0 0 0-3-3 M5 11v1a7 7 0 0 0 14 0v-1 M12 19v3 M9 22h6'),
    'camera': ('拍摄', 'M8 5l1-2h6l1 2h3a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z M12 9a4 4 0 1 0 0 8 4 4 0 0 0 0-8'),
    'ai': ('AI 助手', 'M12 3l2.5 6.5L21 12l-6.5 2.5L12 21l-2.5-6.5L3 12l6.5-2.5z M20 3v3 M18.5 4.5h3'),
    'search': ('搜索', 'M10 3a7 7 0 1 0 0 14 7 7 0 0 0 0-14 M15 15l6 6'),
    'chart': ('统计', 'M4 3v18h17 M8 16v-5 M13 16V7 M18 16v-8'),
    'sync': ('同步', 'M20 10a8 8 0 0 0-14-4L3 9 M3 4v5h5 M4 14a8 8 0 0 0 14 4l3-3 M21 20v-5h-5'),
    'export': ('导出', 'M12 3v12 M8 11l4 4 4-4 M4 14v5a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-5'),
    'image': ('图片', 'M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2 M3 17l6-6 5 5 3-3 4 4 M16 7h.01'),
    'eye_show': ('显示金额', 'M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12z M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6'),
    'eye_hide': ('隐藏金额', 'M3 3l18 18 M9 5.5A11 11 0 0 1 12 5c6 0 10 7 10 7a18 18 0 0 1-3 3.5 M6 6.5A20 20 0 0 0 2 12s4 7 10 7a11 11 0 0 0 5-1.5 M10 10a3 3 0 0 0 4 4'),
    'chevron_right': ('进入', 'M9 5l7 7-7 7'),
    'notification': ('通知', 'M5 17V9a7 7 0 0 1 14 0v8l2 2H3z M9 22h6'),
    'lock': ('安全', 'M7 10V7a5 5 0 0 1 10 0v3 M5 10h14a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1v-9a1 1 0 0 1 1-1 M12 14v3'),
    'info': ('说明', 'M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18 M12 11v6 M12 7h.01'),
    'link': ('关联', 'M10 14l4-4 M8 16l-1 1a4 4 0 0 1-6-6l4-4a4 4 0 0 1 6 0 M16 8l1-1a4 4 0 0 1 6 6l-4 4a4 4 0 0 1-6 0'),
    'logout': ('退出登录', 'M10 3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h5 M8 12h13 M17 8l4 4-4 4'),
    'theme': ('外观', 'M12 3a9 9 0 1 0 0 18h1a2 2 0 0 0 1-3.5 1.5 1.5 0 0 1 1-2.5h3a3 3 0 0 0 3-3 9 9 0 0 0-9-9 M7 10h.01 M10 7h.01 M15 7h.01 M18 10h.01'),
    'calendar': ('日期', 'M7 2v4 M17 2v4 M3 9h18 M5 4h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2 M7 13h2 M15 13h2 M7 17h2 M15 17h2'),
    'receipt': ('票据', 'M5 3h14v18l-3-2-4 2-4-2-3 2z M9 7h6 M9 11h6 M9 15h3'),
    'location': ('位置', 'M12 2a7 7 0 0 0-7 7c0 5 7 13 7 13s7-8 7-13a7 7 0 0 0-7-7 M12 6a3 3 0 1 0 0 6 3 3 0 0 0 0-6'),
    'category_meals': ('三餐', 'M4 3v5a3 3 0 0 0 6 0V3 M7 3v18 M18 21V3c-3 1-4 5-4 8h4'),
    'category_daily': ('日用', 'M3 8h18l-2 13H5z M7 8V6a5 5 0 0 1 10 0v2 M9 12v5 M15 12v5'),
    'category_transport': ('交通', 'M5 3h14a2 2 0 0 1 2 2v13H3V5a2 2 0 0 1 2-2 M3 10h18 M8 4v6 M16 4v6 M6 14h.01 M18 14h.01 M6 18v3 M18 18v3'),
    'category_study': ('学习', 'M12 5v16 M3 4h5a4 4 0 0 1 4 2 4 4 0 0 1 4-2h5v15h-5a5 5 0 0 0-4 2 5 5 0 0 0-4-2H3z'),
    'category_sports': ('运动', 'M6 5v14 M3 8v8 M18 5v14 M21 8v8 M6 12h12'),
    'category_entertainment': ('娱乐', 'M7 7h10a3 3 0 0 1 3 2l2 9a2 2 0 0 1-3 2l-4-3H9l-4 3a2 2 0 0 1-3-2l2-9a3 3 0 0 1 3-2 M8 10v5 M5.5 12.5h5 M16 11h.01 M18 14h.01'),
    'category_shopping': ('网购', 'M6 7h12l2 14H4z M8 8V6a4 4 0 0 1 8 0v2'),
    'category_medical': ('医疗', 'M9 3h6v6h6v6h-6v6H9v-6H3V9h6z'),
    'category_home': ('居家', 'M2 11l10-9 10 9 M5 9v12h14V9 M9 21v-7h6v7'),
    'category_social': ('人情', 'M12 3l3 3H9z M3 9h18v4H3z M5 13v8h14v-8 M12 9v12 M12 8C4 9 4 2 8 3c2 0 3 3 4 5 M12 8c8 1 8-6 4-5-2 0-3 3-4 5'),
    'category_pet': ('宠物', 'M8 13c-1 2-4 3-4 5 0 3 4 4 8 2 4 2 8 1 8-2 0-2-3-3-4-5-2-3-6-3-8 0z M5 6a1.5 2.5 0 1 0 0 5 1.5 2.5 0 0 0 0-5 M10 2a1.5 2.5 0 1 0 0 5 1.5 2.5 0 0 0 0-5 M15 2a1.5 2.5 0 1 0 0 5 1.5 2.5 0 0 0 0-5 M20 6a1.5 2.5 0 1 0 0 5 1.5 2.5 0 0 0 0-5'),
    'category_beauty': ('美妆个护', 'M9 12V5l6-2v9 M8 12h8v9H8z M8 17h8'),
    'category_clothing': ('服饰', 'M8 3a4 4 0 0 0 8 0l6 4-3 5-3-2v11H8V10l-3 2-3-5z'),
    'category_baby': ('母婴', 'M6 3h6a6 6 0 0 1 6 6H6z M6 9v3a6 6 0 0 0 12 0V9h3V6 M6 18a2 2 0 1 0 0 4 2 2 0 0 0 0-4 M18 18a2 2 0 1 0 0 4 2 2 0 0 0 0-4'),
    'category_car': ('汽车', 'M4 10l2-6h12l2 6 M3 10h18v9H3z M3 14h4 M17 14h4 M6 19v2 M18 19v2'),
    'category_digital': ('数码', 'M4 3h16v13H4z M9 16v5 M15 16v5 M7 21h10'),
    'category_insurance': ('保险', 'M12 2l8 3v6c0 5-4 9-8 11-4-2-8-6-8-11V5z M8 12l3 3 5-6'),
    'category_travel': ('旅行', 'M8 6V3h8v3 M5 6h14a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2 M8 10v7 M16 10v7'),
    'category_salary': ('工资', 'M4 6h16v15H4z M9 6V3h6v3 M4 11h16 M10 11v3h4v-3'),
    'category_parttime': ('兼职', 'M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18 M12 7v5l4 3'),
    'category_finance': ('理财', 'M3 18l6-6 4 3 8-10 M15 5h6v6 M3 22h18'),
    'category_other': ('其他', 'M5 12h.01 M12 12h.01 M19 12h.01'),
    'category_reimburse': ('报销', 'M13 3H5v18h14v-9 M8 8h3 M8 12h3 M8 16h6 M16 3v7 M13 7l3 3 3-3'),
    'category_resale': ('二手转卖', 'M3 8h17 M16 4l4 4-4 4 M21 16H4 M8 12l-4 4 4 4'),
    'category_redpacket': ('红包礼金', 'M5 3h14v18H5z M5 8l7 5 7-5 M12 13v4'),
    'account_wallet': ('钱包账户', 'M20 7H6a3 3 0 0 1 0-6h12v6 M3 4v14a3 3 0 0 0 3 3h14V7 M20 12h-6v5h6 M17 14.5h.01'),
    'account_bank_card': ('银行卡', 'M4 4h16a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1 M3 9h18 M7 15h4'),
    'account_credit_card': ('信用卡', 'M4 4h16a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1 M3 9h18 M7 15h4 M16 14v3 M14.5 15.5h3'),
    'account_cash': ('现金', 'M2 5h20v14H2z M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8 M5 9v6 M19 9v6'),
    'account_investment': ('投资账户', 'M4 21V11 M10 21V7 M16 21V3 M3 21h18 M18 10l3-3'),
    'account_other': ('其他账户', 'M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18 M7 12h.01 M12 12h.01 M17 12h.01'),
    'settings': ('设置', 'M9 3h6v3l3 2 3-1 2 5-3 2v3l-4 3-2-2h-4l-2 2-4-3v-3l-3-2 2-5 3 1 3-2z M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6'),
    'budget': ('预算', 'M12 3v9h9a9 9 0 1 1-9-9 M16 3a7 7 0 0 1 5 5h-5z'),
    'help': ('帮助', 'M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18 M9 8a3 3 0 0 1 6 1c0 2-3 2-3 4 M12 17h.01'),
}

def generate():
    OUT.mkdir(parents=True, exist_ok=True)
    for name, (label, path) in ICONS.items():
        svg = f'''<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" role="img" aria-labelledby="title">
  <title id="title">{label}</title>
  <path d="{path}" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round"/>
</svg>
'''
        vector = f'''<?xml version="1.0" encoding="utf-8"?>
<!-- 由 resource/mobile/generate_icons.py 生成，请修改 SVG 同源路径后重新生成。 -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="@android:color/transparent" android:strokeColor="#FF555555"
        android:strokeWidth="1.75" android:strokeLineCap="round" android:strokeLineJoin="round"
        android:pathData="{path}"/>
</vector>
'''
        ET.fromstring(svg)
        ET.fromstring(vector)
        (OUT / f'ic_{name}.svg').write_text(svg, encoding='utf-8')
        (DRAWABLE / f'ic_{name}.xml').write_text(vector, encoding='utf-8')
    cards = ''.join(f'<div><img src="icons/ic_{name}.svg"/><span>{label}</span><small>{name}</small></div>' for name, (label, _) in ICONS.items())
    (OUT.parent / 'preview.html').write_text(f'''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>记一笔 · 移动端图标</title>
<style>body{{font:14px system-ui;color:#303840;background:#f5f7fa;padding:40px;margin:0}}h1{{font-size:24px}}p{{color:#667}}main{{display:grid;grid-template-columns:repeat(8,1fr);gap:12px}}main div{{background:white;border-radius:16px;display:flex;align-items:center;flex-direction:column;padding:18px 8px;gap:10px}}img{{width:28px;height:28px}}small{{font-size:10px;color:#789}}footer{{margin-top:24px;color:#678}}</style>
<h1>记一笔 · 移动端图标</h1><p>统一 24 × 24 网格 · 1.75 线宽 · 圆角端点 · 支持主题着色</p><main>{cards}</main><footer>{len(ICONS)} 枚原创 SVG，同源生成 Android VectorDrawable。</footer></html>''', encoding='utf-8')
    print(f'已生成并验证 {len(ICONS)} 枚 SVG / Android 向量资源')

if __name__ == '__main__':
    generate()
