# RinklNote Android 答辩 PPT 部署文档

本文说明如何构建、预览和发布 RinklNote 的 12 页 Android 优先答辩演示页。演示页是 Web SPA 中的 `/defense` 路由，静态构建后即可展示；只有 Web 控制台的账单、图表和机器人管理功能需要额外配置 Ktor API。

## 1. 部署范围

| 项目 | 值 |
|---|---|
| 源码目录 | `web/` |
| 构建工具 | Vite 6 + Vue 3 + TypeScript |
| Node.js | 建议 20 LTS 或更高版本的 LTS |
| 构建命令 | `npm ci && npm run build` |
| 产物目录 | `web/dist/` |
| 答辩入口 | `/defense?page=1` |
| 翻页 | 页面按钮、键盘 `←` / `→`、`PageUp` / `PageDown` |
| 演示模式 | 页面右上角「开始演示」，或播放栏全屏按钮 |

答辩页不依赖登录和服务端数据，可以单独部署为静态站点。部署整套 Web 控制台时，仍需将 `/api` 反向代理到 Ktor 服务端。

## 2. 本地构建与预览

在仓库根目录执行：

```bash
cd web
npm ci
npm run typecheck
npm test -- --run
npm run build
npm run preview -- --host 127.0.0.1
```

看到 Vite 输出本地地址后，打开：

```text
http://127.0.0.1:4173/defense?page=1
```

不要直接双击 `dist/index.html`。项目使用 `createWebHistory()`，通过 `file://` 打开会缺少路由回退和模块服务能力。

## 3. 静态服务器部署

将构建产物复制到静态站点目录，例如：

```bash
sudo mkdir -p /srv/rinklnote/web
sudo rsync -a --delete web/dist/ /srv/rinklnote/web/
```

Nginx 示例配置如下。`try_files` 是 SPA 路由正常打开 `/defense`、`/defense?page=7` 的必要配置：

```nginx
server {
    listen 80;
    server_name demo.example.com;
    root /srv/rinklnote/web;
    index index.html;

    location / {
        try_files $uri $uri/ /index.html;
    }

    # 整套 Web 控制台需要 API 时启用；只展示答辩页可省略。
    location /api/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

检查并重载 Nginx：

```bash
sudo nginx -t
sudo systemctl reload nginx
```

如果使用 Vercel、Netlify 或其他静态托管服务，构建命令设为 `npm ci && npm run build`，发布目录设为 `web/dist`，并添加所有路径回退到 `/index.html` 的 rewrite。没有 SPA rewrite 时，首页可以打开，但刷新 `/defense?page=1` 会返回 404。

## 4. 发布前检查

每次发布前在构建机执行：

```bash
cd web
npm ci
npm run typecheck
npm test -- --run
npm run build
```

浏览器冒烟检查：

1. 打开 `/defense?page=1`，确认封面和右侧 Android 产品视觉正常。
2. 依次跳转 1～12 页，确认标题、故事进度条和缩略导航同步。
3. 用 `←` / `→` 翻页，观察方向进入动效、卡片错峰和手机轻微漂浮动效。
4. 点击「开始演示」，确认全屏失败时仍能进入演示布局；按 `Esc` 可退出。
5. 点击「导出 / 打印」，确认 12 页按页分页且隐藏演示控制区。
6. 在约 390px 宽度下检查 `/defense?page=7`，确认没有横向滚动条，QQ Bot 页面内容可读。
7. 在系统开启“减少动态效果”后刷新，确认页面仍可读且动画停止。

## 5. 缓存与回滚

项目启用了 PWA 应用壳缓存。发布新版本后若浏览器仍显示旧版：

1. 等待 service worker 自动更新，或在浏览器开发者工具中注销旧 service worker 后硬刷新。
2. 确认 `dist/sw.js`、`dist/manifest.webmanifest` 与同一批构建产物一起发布。
3. 不要单独替换某一个带 hash 的 JS/CSS 文件。

回滚时保留上一份完整 `dist/` 目录，重新执行 `rsync -a --delete`。答辩页不写入用户账单数据，回滚只影响演示前端版本。

## 6. 发布限制

- 不要把 `JWT_SECRET`、`DEEPSEEK_API_KEY` 或任何机器人凭据放入 `web/`、静态资源或提交记录。
- 答辩演示页只展示产品原型和已记录的 Android 验证证据；页面中的 301/301、Debug APK 等数字应在实际测试记录变化后同步更新。
- 生产域名必须使用 HTTPS，尤其是全屏、PWA 和后续 API 登录功能需要安全上下文。
- 部署完成后把访问地址和构建提交号记录到发布记录，便于答辩现场快速回滚。
