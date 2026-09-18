# 生产部署清单（2026-09-18 · Task 0.8）

> ⚠️ **生产部署待人工执行**：本清单只是操作手册，仓库侧绝不自动 ssh / 触碰部署机（jmbot 118.31.184.221）。
> 所有步骤由管理员按序手工执行，每步完成后在本文末尾「执行记录」打勾留痕。

## 0. 本次交付物（代码侧已就绪）

| 交付物 | 位置 | 说明 |
|---|---|---|
| 每日备份脚本 | `server/scripts/backup.sh` | H2 BACKUP TO / pg_dump / 文件快照 三模式，轮转保留 14 份 |
| 全局异常告警 | `server/.../services/AlertNotifier.kt` + `plugins/ErrorHandling.kt` | 未捕获异常（500 类）经 Bot 通道推主账号：路径+异常摘要，**不含请求体**；同路径同异常 1 分钟限 1 条 |
| admin 只读管理面 | `/api/admin/*`（已有） | 四页：运维大盘 / 用户管理 / 机器人运维 / 推送历史；通道健康度 `GET /api/admin/push/health`（Task 0.7） |

## 1. 前置条件（人工核对）

- [ ] `main` 分支三端测试全绿（Server `./gradlew :server:test` / App `:app:testDebugUnitTest` / Web `npm run typecheck && npm run test`）。
- [ ] 部署机可从 GitHub 拉取（`git remote -v` 里 `origin`），或按既有流程由本地推送 `server` 远端。
- [ ] Bot 已配置且管理员账号至少绑定一个**可推送通道**（飞书 / 企微 / QQ；订阅号只收不推，不能做告警出口）。

## 2. 部署服务端（按既有流程）

1. 合并 `feat/wave-server` → `main`（按仓库 Git 工作流核对上游后再合并）。
2. 推送 `main` 到部署机仓库（`git push server main` 或既有发布流程）。
3. 部署机上重启服务进程（systemd / 既有守护方式），确认 `GET /api/health`（或日志「Database initialized」）正常。

## 3. 配置 ADMIN_IDENTITIES（管理面 + 异常告警共用）

- 部署机环境变量新增（逗号分隔手机号 / userId，两者都认）：

  ```bash
  ADMIN_IDENTITIES=<主账号手机号>
  ```

- 重启服务生效。验证：
  - [ ] 管理员登录 Web `/admin` 四页可访问，他人访问一律 404/403（不暴露存在性）；
  - [ ] `GET /api/admin/overview` 只回聚合计数，手机号掩码（138****1234）。

## 4. 安装每日备份 crontab

部署机上（示例为每天 03:17，避开整点高峰）：

```bash
crontab -e
# 追加一行（按部署机实际 DATABASE_URL / 模式调整环境变量）：
17 3 * * * cd /opt/rinklnote/server && DATABASE_URL='<生产JDBC URL>' DATABASE_PASSWORD='<密码>' BACKUP_DIR=/opt/rinklnote/backups RETAIN=14 ./scripts/backup.sh >> /opt/rinklnote/backups/backup.log 2>&1
```

- PostgreSQL 模式需部署机装有 `pg_dump`（版本 ≥ 服务端 PG 大版本）；PG 在 docker 里时加 `PG_CONTAINER=<容器名>`。
- H2 文件库模式需 `H2_JAR` 指向 h2*.jar（Gradle 缓存或部署机 lib 目录可找到）。
- 首次手动跑一遍确认产出非空：`ls -la <BACKUP_DIR>` 见当日文件、`LATEST.txt` 已刷新。

## 5. 恢复演练（必做一次，之后每季度一次）

1. 取最近一份备份，还原到**临时库**（绝不直接覆盖生产）：
   - PG：`pg_restore -h localhost -U rinklnote -d rinklnote_drill --no-owner <备份.dump>`；
   - H2：把备份 zip 对应的 `.mv.db` 换成临时路径名，用 `org.h2.tools.Shell` 连上做 `SELECT COUNT(*) FROM bills` 级抽查；
   - 文件快照：tar 解到临时目录。
2. 起一个临时端口的服务实例指向临时库（`DATABASE_URL=... PORT=8081`），登录后抽查：
   - [ ] 账单总数与生产大盘一致（±当日增量）；
   - [ ] 任选用户账单分类/金额抽查 3 条，与生产一致；
   - [ ] `users` 数量一致。
3. 演练通过后删除临时库与临时实例，在本文末尾记录日期与结果。

## 6. 异常告警验证（人为触发一次）

- 用非管理员账号调一个会 500 的路径（或临时在测试环境注入 `throw IllegalStateException` 之外的运行时异常路由），确认：
  - [ ] 主账号绑定的 Bot 通道收到「🚨 小盘异常汇报」（含方法+路径+异常类名摘要）；
  - [ ] 告警内容里**没有**请求体 / SQL 参数等敏感信息；
  - [ ] 1 分钟内重复触发不重复推送（限流生效）。

## 7. 执行记录（部署后填写）

| 步骤 | 执行人 | 日期 | 结果 |
|---|---|---|---|
| §2 服务端部署 | | | |
| §3 ADMIN_IDENTITIES | | | |
| §4 crontab 备份 | | | |
| §5 恢复演练 | | | |
| §6 异常告警验证 | | | |
