"""生成细分类 SVG、Android 矢量资源和分类映射，并检查预置分类覆盖率。"""

import argparse
import json
import re
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
DRAWABLES = ROOT / "app/src/main/res/drawable"
MAPPING = ROOT / "app/src/main/java/com/example/rinklnote/ui/util/SubcategoryIcons.kt"

# 统一 24×24 网格、2px 圆角描边；图形仅用路径，SVG 与 Android 共用同一份几何。
PATHS = {
    "none": ["M4 4h6v6H4z M14 4h6v6h-6z M4 14h6v6H4z M14 14h6v6h-6z"],
    "breakfast": ["M4 14h12v4a3 3 0 0 1-3 3H7a3 3 0 0 1-3-3z M16 15h2a2 2 0 0 1 0 4h-2", "M6 10a5 5 0 0 1 10 0 M11 2v2 M3 5l2 2 M19 5l-2 2"],
    "lunch": ["M3 12h18a9 9 0 0 1-18 0z M7 21h10", "M8 3c-3 3 3 4 0 7 M12 2c-3 3 3 4 0 7 M16 3c-3 3 3 4 0 7"],
    "dinner": ["M3 13h18a9 9 0 0 1-18 0z M7 22h10", "M15 2a5 5 0 1 0 6 6 5 5 0 0 1-6-6z"],
    "snack": ["M20 12a8 8 0 1 1-8-8 4 4 0 0 0 8 8z", "M7 9h.1 M10 16h.1 M16 16h.1 M6 14h.1 M13 11h.1"],
    "takeaway": ["M5 8h14l1 13H4z M8 8V6a4 4 0 0 1 8 0v2", "M9 12v5 M15 12v5 M8 14h2 M14 14h2"],
    "drink": ["M5 8h14l-2 13H7z M4 8h16 M13 8l2-6h4", "M7 13h10"],
    "bus": ["M5 3h14v15H5z M5 11h14 M12 3v8 M7 18v3 M17 18v3", "M8 15h.1 M16 15h.1"],
    "train": ["M7 3h10a2 2 0 0 1 2 2v11a3 3 0 0 1-3 3H8a3 3 0 0 1-3-3V5a2 2 0 0 1 2-2z M5 11h14 M8 19l-2 3 M16 19l2 3", "M8 15h.1 M16 15h.1"],
    "taxi": ["M3 11l3-6h12l3 6v7H3z M3 11h18 M8 5V2h8v3 M6 18v3 M18 18v3", "M6 14h2 M16 14h2"],
    "fuel": ["M4 4h9v17H4z M4 11h9 M3 21h12 M16 5l3 3v10a2 2 0 0 1-4 0v-4h-2 M17 6v4h2"],
    "parking": ["M4 3h16v18H4z", "M9 17V7h4a3 3 0 0 1 0 6H9"],
    "ticket": ["M3 6h18v4a2 2 0 0 0 0 4v4H3v-4a2 2 0 0 0 0-4z M15 7v2 M15 11v2 M15 15v2", "M7 12h4"],
    "bike": ["M9 5h4 M11 5l4 12 M6 17l5-8h6 M16 5h2l2 4", "M9 17a4 4 0 1 1-8 0 4 4 0 0 1 8 0 M23 17a4 4 0 1 1-8 0 4 4 0 0 1 8 0"],
    "laundry": ["M4 3h16v18H4z M4 7h16 M7 5h.1 M10 5h.1", "M17 14a5 5 0 1 1-10 0 5 5 0 0 1 10 0 M8 14q2-2 4 0t4 0"],
    "wash": ["M5 4v6a3 3 0 0 0 6 0V4 M5 7h6 M8 13v8 M17 7h4v14h-4z M18 3h2v4"],
    "sofa": ["M5 12V7a3 3 0 0 1 3-3h8a3 3 0 0 1 3 3v5 M4 12H2v7h20v-7h-2 M4 12v4h16v-4 M5 19v3 M19 19v3"],
    "tissue": ["M4 11h16v10H4z M8 11l1-8h6l1 8 M10 7h4 M9 15h6"],
    "book": ["M12 6C9 3 5 3 2 4v16c4-1 7-1 10 2 3-3 6-3 10-2V4c-3-1-7-1-10 2z M12 6v16"],
    "stationery": ["M4 17l-1 4 4-1L20 7l-3-3z M14 7l3 3 M5 17l2 2 M10 21h11"],
    "training": ["M2 8l10-5 10 5-10 5z M6 11v6c4 3 8 3 12 0v-6 M22 8v9"],
    "exam": ["M5 3h10l4 4v14H5z M15 3v5h4 M8 11h5 M8 15l2 2 4-4"],
    "lesson": ["M3 4h18v13H3z M12 17v4 M7 21h10 M7 8h10 M7 12h6"],
    "dumbbell": ["M2 9v6 M5 5v14 M19 5v14 M22 9v6 M5 12h14"],
    "run": ["M14 4a2 2 0 1 1-4 0 2 2 0 0 1 4 0", "M8 10l4-3 4 5h4 M12 8l-3 7 5 3v4 M9 15l-5 7 M4 10l4-1"],
    "ball": ["M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0 M3 12h18 M12 3v18 M5 5q10 7 0 14 M19 5q-10 7 0 14"],
    "film": ["M3 4h18v16H3z M7 4v16 M17 4v16 M3 9h4 M3 15h4 M17 9h4 M17 15h4", "M10 8l5 4-5 4z"],
    "game": ["M7 7h10c2 0 3 2 4 6s0 7-3 5l-3-3H9l-3 3c-3 2-4-1-3-5s2-6 4-6z M6 11h4 M8 9v4 M16 11h.1 M18 13h.1"],
    "travel": ["M5 6h14v14H5z M9 6V3h6v3 M8 20v2 M16 20v2 M9 9v8 M15 9v8"],
    "stage": ["M4 4h16v16H4z M4 7l4 4-4 6 M20 7l-4 4 4 6 M8 20v-4h8v4 M12 8v6"],
    "microphone": ["M15 6a3 3 0 0 1-6 0 3 3 0 0 1 6 0v6a3 3 0 0 1-6 0V6 M6 11v1a6 6 0 0 0 12 0v-1 M12 18v4 M8 22h8"],
    "bag": ["M4 7h16l1 14H3z M8 7V6a4 4 0 0 1 8 0v1"],
    "cart": ["M2 3h3l3 13h11l3-9H6 M10 21a1 1 0 1 1-2 0 1 1 0 0 1 2 0 M20 21a1 1 0 1 1-2 0 1 1 0 0 1 2 0"],
    "parcel": ["M3 6l9-4 9 4v13l-9 3-9-3z M3 6l9 4 9-4 M12 10v12 M8 4l9 4v5"],
    "clinic": ["M5 3h14v19H5z M9 22v-5h6v5 M9 8h6 M12 5v6"],
    "capsule": ["M5 19a5 5 0 0 1 0-7l7-7a5 5 0 0 1 7 7l-7 7a5 5 0 0 1-7 0z M8 9l7 7"],
    "health": ["M20 6c-3-4-7-2-8 0-1-2-5-4-8 0-4 5 3 10 8 15 5-5 12-10 8-15z", "M3 12h5l2-3 3 7 2-4h6"],
    "tooth": ["M5 3c3-2 4 1 7 1s4-3 7-1c4 3-1 18-4 18-2 0-1-8-3-8s-1 8-3 8C6 21 1 6 5 3z"],
    "glasses": ["M2 8v7a4 4 0 0 0 8 0v-3H2 M22 8v7a4 4 0 0 1-8 0v-3h8 M10 13q2-2 4 0"],
    "house": ["M2 11l10-9 10 9 M5 9v13h14V9 M9 22v-8h6v8"],
    "mortgage": ["M3 10l9-7 9 7 M5 9v12h14V9 M9 21v-5h6v5 M9 12h6"],
    "building": ["M5 2h14v20H5z M9 6h.1 M15 6h.1 M9 10h.1 M15 10h.1 M9 14h.1 M15 14h.1 M10 22v-4h4v4"],
    "utilities": ["M8 2C6 6 3 9 3 12a5 5 0 0 0 10 0C13 9 10 6 8 2z", "M18 5l-4 8h5l-2 9 6-12h-5z"],
    "wifi": ["M2 8a16 16 0 0 1 20 0 M5 12a11 11 0 0 1 14 0 M8 16a6 6 0 0 1 8 0 M12 20h.1"],
    "packet": ["M5 3h14v18H5z M5 6l7 5 7-5 M9 14h6 M12 12v6"],
    "gift": ["M3 8h18v5H3z M5 13v9h14v-9 M12 8v14", "M12 8C3 8 5 0 9 3l3 5c9 0 7-8 3-5z"],
    "dining": ["M3 4v6a2 2 0 0 0 4 0V4 M5 4v18 M11 4v9h3V4c-2 1-3 4-3 9 M14 13v9 M18 8h4 M20 6v4"],
    "giftcard": ["M3 5h18v15H3z M3 10h18 M14 5v15 M7 15h3 M14 7c-5 0-4-5-1-3l1 3c5 0 4-5 1-3z"],
    "petfood": ["M3 15h18l-2 6H5z M12 7c-5 0-7 6-4 6 2 0 2-1 4-1s2 1 4 1c3 0 1-6-4-6z M7 3h.1 M11 2h.1 M15 3h.1 M18 6h.1"],
    "petmedical": ["M5 8c-2-4-3-4-3 0 M19 8c2-4 3-4 3 0 M5 8c4-4 10-4 14 0v7c0 6-14 6-14 0z M9 13h6 M12 10v6"],
    "pettoy": ["M7 5a3 3 0 1 0-4 4l8 8a3 3 0 1 0 4 4 3 3 0 1 0 6-4l-8-8a3 3 0 1 0-6-4z"],
    "petwash": ["M6 12h12l-2 9H8z M12 2C9 6 8 8 12 10c4-2 3-4 0-8z M4 15h2 M18 15h2"],
    "cosmetics": ["M4 9h6v13H4z M5 9V3l4 2v4 M15 10h5v12h-5z M14 7h7v3h-7z"],
    "scissors": ["M8 16a3 3 0 1 1-6 0 3 3 0 0 1 6 0 M22 16a3 3 0 1 1-6 0 3 3 0 0 1 6 0 M7 14L19 2 M17 14L5 2"],
    "mirror": ["M18 9a6 7 0 1 1-12 0 6 7 0 0 1 12 0 M12 16v6 M8 22h8 M9 7l4-3"],
    "clothes": ["M7 3l-5 5 4 4 2-2v12h8V10l2 2 4-4-5-5c-2 3-8 3-10 0z"],
    "shoe": ["M3 13l2-7h5l2 6 7 2 3 4v3H2v-5z M2 17h18 M10 10l-3 2 M12 13l-3 2"],
    "jewelry": ["M4 3c0 9 4 13 8 13s8-4 8-13 M9 18l3-3 3 3-3 4z"],
    "bottle": ["M8 8h8l2 4v9H6v-9z M9 8V4h6v4 M11 4V2h2v2 M13 13h5 M14 17h4"],
    "toy": ["M3 12h9v10H3z M12 12h9v10h-9z M7 2h10v10H7z M6 17h3 M15 16l3 3 M18 16l-3 3"],
    "early": ["M3 5h18v16H3z M3 11h18 M9 5v16 M15 5v16 M5 8h2 M11 14h2 M17 17h2"],
    "wrench": ["M14 3a6 6 0 0 0-7 8l-5 6a3 3 0 0 0 4 4l6-6a6 6 0 0 0 8-7l-4 4-4-4z"],
    "carshield": ["M2 11l3-5h9l3 5v7H2z M2 11h15 M5 18v3 M5 14h2", "M18 10l4 2v5c0 3-4 5-4 5s-4-2-4-5v-5z"],
    "carwash": ["M3 12l3-5h12l3 5v7H3z M3 12h18 M6 19v3 M18 19v3 M6 15h2 M16 15h2 M7 2v2 M12 1v3 M17 2v2"],
    "devices": ["M3 3h16v12H3z M11 15v5 M7 20h6 M16 10h6v12h-6z M18 19h2"],
    "plug": ["M7 2v6 M17 2v6 M5 8h14v3a7 7 0 0 1-14 0z M12 18v4"],
    "member": ["M3 5h18v15H3z M7 10l2-2 2 2-2 2z M14 9h4 M14 13h4 M6 17h5"],
    "shield": ["M12 2l8 3v7c0 6-8 10-8 10S4 18 4 12V5z M8 12l3 3 5-6"],
    "hotel": ["M4 3h16v19H4z M8 7h2 M14 7h2 M8 11h2 M14 11h2 M9 22v-6h6v6"],
    "landmark": ["M3 9l9-6 9 6H3z M5 9v10 M10 9v10 M14 9v10 M19 9v10 M2 22h20 M3 19h18"],
    "wallet": ["M3 6l15-3v4 M3 7h18v15H3z M16 11h6v7h-6z M19 14h.1"],
    "bonus": ["M16 8a4 4 0 1 1-8 0 4 4 0 0 1 8 0 M8 11l-3 11 7-4 7 4-3-11 M12 6v4 M10 8h4"],
    "subsidy": ["M2 17l4-4h7l3 2-3 3H8 M5 20h9l8-7 M1 21l5-4", "M17 5a3 3 0 1 1-6 0 3 3 0 0 1 6 0"],
    "labor": ["M3 5l6 6-3 3-5-6 M21 3l-6 6 3 3 5-6 M7 12l9 9 M17 11L6 22"],
    "project": ["M3 7h18v14H3z M8 7V3h8v4 M3 12h18 M10 11v3h4v-3"],
    "other": ["M6 12a1 1 0 1 1-2 0 1 1 0 0 1 2 0 M13 12a1 1 0 1 1-2 0 1 1 0 0 1 2 0 M20 12a1 1 0 1 1-2 0 1 1 0 0 1 2 0"],
    "interest": ["M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0 M8 16l8-8 M9 8h.1 M15 16h.1"],
    "fund": ["M12 3v9h9a9 9 0 1 1-9-9z M16 2v6h6a6 6 0 0 0-6-6z"],
    "stock": ["M3 21h18 M5 17v-5 M11 17V9 M17 17V5 M3 8l6-4 4 2 8-4"],
    "refund": ["M4 7l4-4 M4 7l4 4 M4 7h10a6 6 0 0 1 0 12h-3 M8 15h6 M11 12v6"],
    "income": ["M3 8h18v13H3z M15 12h7v5h-7z M8 2v9 M5 8l3 3 3-3"],
    "receipt": ["M5 2l3 2 4-2 4 2 3-2v20l-3-2-4 2-4-2-3 2z M8 8h8 M8 12h8 M8 16h5"],
    "travelreceipt": ["M5 2h14v20l-4-2-3 2-3-2-4 2z M8 7l8 4-8 4 M8 11h8 M9 18h6"],
    "resale": ["M3 4h8l10 10-7 7L3 10z M7 8h.1 M11 11l5 5 M11 16l5-5"],
    "payment": ["M3 6h18v15H3z M3 10h18 M12 13v5 M9 16l3 3 3-3"],
    "receivepacket": ["M4 8h16v14H4z M4 10l8 5 8-5 M12 2v8 M9 7l3 3 3-3"],
    "lucky": ["M12 2l3 6 7 1-5 5 1 7-6-3-6 3 1-7-5-5 7-1z M10 11h4 M12 9v6"],
    "giftmoney": ["M3 8h18v5H3z M5 13v8h14v-8 M12 8v13 M8 3l4 5 4-5 M10 17h4"],
}

# 每个预置子标签都有独立可编辑的 SVG 文件，同义图形仅复用几何定义。
CATALOG = {
    "三餐": ("meals", [("早餐", "breakfast"), ("午餐", "lunch"), ("晚餐", "dinner"), ("零食", "snack"), ("外卖", "takeaway"), ("饮品", "drink")]),
    "交通": ("transport", [("公交", "bus"), ("地铁", "train"), ("打车", "taxi"), ("加油", "fuel"), ("停车费", "parking"), ("火车机票", "ticket"), ("共享单车", "bike")]),
    "日用": ("daily", [("洗衣", "laundry"), ("洗漱", "wash"), ("家居", "sofa"), ("纸品清洁", "tissue")]),
    "学习": ("study", [("书籍", "book"), ("文具", "stationery"), ("培训", "training"), ("考试", "exam"), ("课程", "lesson")]),
    "运动": ("sports", [("健身", "dumbbell"), ("跑步", "run"), ("球类", "ball")]),
    "娱乐": ("entertainment", [("电影", "film"), ("游戏", "game"), ("旅游", "travel"), ("演出", "stage"), ("KTV", "microphone")]),
    "网购": ("shopping", [("淘宝", "bag"), ("京东", "cart"), ("快递", "parcel")]),
    "医疗": ("medical", [("门诊", "clinic"), ("药品", "capsule"), ("体检", "health"), ("口腔", "tooth"), ("眼镜", "glasses")]),
    "居家": ("home", [("房租", "house"), ("房贷", "mortgage"), ("物业", "building"), ("水电燃气", "utilities"), ("宽带", "wifi")]),
    "人情": ("social", [("红包礼金", "packet"), ("礼物", "gift"), ("请客", "dining"), ("随礼", "giftcard")]),
    "宠物": ("pet", [("粮食", "petfood"), ("医疗", "petmedical"), ("用品", "pettoy"), ("洗护", "petwash")]),
    "美妆个护": ("beauty", [("护肤彩妆", "cosmetics"), ("理发美发", "scissors"), ("美容", "mirror")]),
    "服饰": ("clothing", [("衣裤", "clothes"), ("鞋帽", "shoe"), ("配饰", "jewelry")]),
    "母婴": ("baby", [("奶粉尿布", "bottle"), ("玩具", "toy"), ("早教", "early")]),
    "汽车": ("car", [("加油", "fuel"), ("保养维修", "wrench"), ("保险", "carshield"), ("洗车", "carwash")]),
    "数码": ("digital", [("手机电脑", "devices"), ("配件", "plug"), ("软件会员", "member")]),
    "保险": ("insurance", [("社保商保", "shield"), ("车险", "carshield")]),
    "旅行": ("travel", [("机票火车", "ticket"), ("酒店", "hotel"), ("景点门票", "landmark")]),
    "工资": ("salary", [("基本工资", "wallet"), ("奖金", "bonus"), ("补贴", "subsidy")]),
    "兼职": ("parttime", [("劳务", "labor"), ("项目", "project"), ("其他", "other")]),
    "理财": ("finance", [("利息", "interest"), ("基金", "fund"), ("股票", "stock")]),
    "其他": ("other", [("红包", "packet"), ("返还", "refund"), ("其他收入", "income")]),
    "报销": ("reimburse", [("差旅报销", "travelreceipt"), ("日常报销", "receipt")]),
    "二手转卖": ("resale", [("闲置出售", "resale"), ("回款", "payment")]),
    "红包礼金": ("redpacket", [("收红包", "receivepacket"), ("压岁钱", "lucky"), ("礼金", "giftmoney")]),
}


def records():
    yield "", "不细分", "none", "none"
    for category, (prefix, options) in CATALOG.items():
        for name, shape in options:
            yield category, name, f"{prefix}_{shape}", shape


def generate():
    (HERE / "svg").mkdir(parents=True, exist_ok=True)
    rows = []
    for category, name, key, shape in records():
        paths = PATHS[shape]
        svg_paths = "\n".join(f'  <path d="{path}"/>' for path in paths)
        svg = ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" '
               'stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">\n'
               + svg_paths + '\n</svg>\n')
        (HERE / "svg" / f"{key}.svg").write_text(svg, encoding="utf-8")
        vector_paths = "\n".join(f'    <path android:pathData="{path}" android:fillColor="@android:color/transparent" '
                                  'android:strokeColor="#FF000000" android:strokeWidth="2" '
                                  'android:strokeLineCap="round" android:strokeLineJoin="round"/>' for path in paths)
        vector = ('<?xml version="1.0" encoding="utf-8"?>\n'
                  '<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" '
                  'android:viewportWidth="24" android:viewportHeight="24">\n' + vector_paths + '\n</vector>\n')
        (DRAWABLES / f"ic_subcategory_{key}.xml").write_text(vector, encoding="utf-8")
        rows.append({"category": category, "name": name, "svg": f"svg/{key}.svg", "drawable": f"ic_subcategory_{key}"})
    (HERE / "catalog.json").write_text(json.dumps(rows, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    kotlin = ['package com.example.rinklnote.ui.util', '', 'import androidx.annotation.DrawableRes',
              'import com.example.rinklnote.R', '', '/** 由 SVG 图标目录生成；按母分类区分同名子标签，未知自建标签回落到母分类图标。 */',
              '@DrawableRes', 'fun subcategoryIconRes(categoryName: String, subcategoryName: String?): Int {',
              '    if (subcategoryName == null || subcategoryName == "不细分") return R.drawable.ic_subcategory_none',
              '    return subcategoryIcons[categoryName]?.get(subcategoryName) ?: categoryIconRes(categoryName)', '}', '',
              'private val subcategoryIcons = mapOf(']
    for category, (prefix, options) in CATALOG.items():
        kotlin.append(f'    "{category}" to mapOf(')
        kotlin.extend(f'        "{name}" to R.drawable.ic_subcategory_{prefix}_{shape},' for name, shape in options)
        kotlin.append('    ),')
    kotlin.extend([')', ''])
    MAPPING.write_text("\n".join(kotlin), encoding="utf-8")


def validate():
    source = (ROOT / "app/src/main/java/com/example/rinklnote/data/repository/BillRepositoryImpl.kt").read_text(encoding="utf-8")
    seed = source.split("val subMap = mapOf(", 1)[1].split("val existing", 1)[0]
    expected = {(category, name) for category, names in re.findall(r'"([^"]+)" to listOf\(([^\n]+)\)', seed)
                for name in re.findall(r'"([^"]+)"', names)}
    actual = {(category, name) for category, (_, options) in CATALOG.items() for name, _ in options}
    if actual != expected:
        raise ValueError(f"细分类映射与种子不符：缺失 {expected - actual}，多余 {actual - expected}")
    for _, _, key, shape in records():
        svg = ET.parse(HERE / "svg" / f"{key}.svg").getroot()
        vector = ET.parse(DRAWABLES / f"ic_subcategory_{key}.xml").getroot()
        assert svg.attrib["viewBox"] == "0 0 24 24" and svg.attrib["aria-hidden"] == "true"
        svg_paths = [p.attrib["d"] for p in svg]
        vector_paths = [p.attrib["{http://schemas.android.com/apk/res/android}pathData"] for p in vector]
        assert svg_paths == vector_paths == PATHS[shape], key
    print(json.dumps({"subcategories": len(actual), "parents": len(CATALOG), "svg_files": len(list(records())),
                      "xml_valid": True, "geometry_consistent": True}, ensure_ascii=False))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="仅验证，不改写资源")
    if not parser.parse_args().check:
        generate()
    validate()
