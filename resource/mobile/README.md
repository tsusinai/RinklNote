# 移动端标识与图标

已选定 Logo：**C「收支页签」**。两块交错的页签通过留白形成平衡，替换原二次元启动图标。应用内保留现有主题蓝色及用户自定义颜色。

## 资源和应用位置

- `brand/symbol-blue.svg`：蓝色标识原稿；`symbol-black.svg` / `symbol-white.svg`：单色及反白。
- `brand/app-icon.svg`：圆角方形应用图标原稿；`app-icon-512.png`：预览。
- `icons/`：62 枚原创线性 SVG，覆盖 25 个账单分类、6 种通用账户、底部导航和常用操作。
- `preview.html`：图标总览；`brand/preview.html`：品牌的大小、背景和应用场景检查。
- `logo-concepts/`：保留初稿、修正稿和用户选择的方案，便于后续比较。

Android 使用原生 VectorDrawable，不增加 SVG 运行时依赖。新标识用于自适应启动图标（含 Android 13 单色图标）、5 档兼容启动图标、登录页、灵动滑块、通知图标和成就兜底。挑战页以预算图标替换二次元吉祥物；旧二次元位图已移出 App 资源。

## 使用约定

- 品牌蓝：浅蓝 `#7EC1FC`，应用启动背景；蓝色 `#5AA8E8`，浅背景上的独立标识。黑白单色版本均可用。
- 标识外围至少保留图形宽度的 1/8 留白；最小展示尺寸 16px，常规 UI 推荐 24px 以上。
- 启动图标由系统负责外形裁切，前景保留在 108 × 108 的中心安全区；不要在自适应前景中添加圆角背景。
- 不添加阴影、渐变、描边，不拉伸标识，不在留白中插入文字或其他符号。
- 功能图标采用 24 × 24 网格、1.75 线宽、圆角端点；SVG 使用 `currentColor`，Compose 使用主题 tint。三方品牌图标保留原有品牌标识。
- 默认头像继续使用用户昵称的首字；用户上传头像不受品牌替换影响。

## 维护

在仓库根目录运行：

```powershell
python resource/mobile/generate_icons.py
python resource/mobile/build_brand.py
# 仅在需要重新生成兼容位图时运行，需要 CairoSVG 和 Pillow：
python resource/mobile/build_brand.py --raster
```

SVG 和 Android 图标共用路径源，禁止只修改其中一端。修改后检查 XML 和预览，并构建 `:app:assembleDebug`。
