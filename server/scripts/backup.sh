#!/usr/bin/env bash
# ============================================================================
# RinklNote 服务端每日备份脚本（2026-09-18 Task 0.8）
#
# 用途：crontab 每日凌晨执行，把数据库备份到 BACKUP_DIR 并轮转保留最新 RETAIN 份。
# 生产部署为人工操作（本仓库绝不 ssh 部署机），安装步骤见
# docs/deploy-checklist-2026-09-18.md「备份与恢复」一节。
#
# 支持三种后端（按 DATABASE_URL 自动判定，也可用 BACKUP_MODE 强制指定）：
#   1. H2 文件库（jdbc:h2:file:...）→ 走 H2 官方 Backup 工具（等价 BACKUP TO），
#      产出单文件 zip，热备安全（H2 Backup 工具在库使用中也能一致性读取）。
#   2. PostgreSQL（jdbc:postgresql://...）→ pg_dump 自定义格式（-Fc，压缩+可 pg_restore）。
#   3. 文件快照（兜底/BACKUP_MODE=file）→ 直接对 DATA_DIR 打 tar.gz 快照
#      （适合内嵌库文件目录；快照期间服务仍在写，属于最终一致性快照，恢复演练必做）。
#
# 环境变量：
#   DATABASE_URL  JDBC 连接串（与 server 运行时一致；默认读 application.conf 的 PG 值）
#   DB_USER/DB_PASSWORD  PG 认证（H2 文件库通常无需密码）
#   BACKUP_MODE   h2 | postgres | file（默认按 DATABASE_URL 自动判定）
#   BACKUP_DIR    备份输出目录（默认 <仓库>/server/backups）
#   RETAIN        轮转保留份数（默认 14）
#   DATA_DIR      file 模式的快照源目录
#   H2_JAR        h2*.jar 路径（H2 模式必填；Gradle 缓存里找，如
#                 ~/.gradle/**/h2-2.3.232.jar，或部署机自带的 lib 目录）
#   PG_CONTAINER  可选：PG 跑在 docker 里时填容器名，用 docker exec 调 pg_dump
#
# 退出码：0 成功；非 0 失败（crontab 可据此发邮件/接告警）。
# ============================================================================
set -euo pipefail

# ---- 可调参数 ----
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="$(dirname "$SCRIPT_DIR")"                     # server/
BACKUP_DIR="${BACKUP_DIR:-$APP_DIR/backups}"
RETAIN="${RETAIN:-14}"
DATABASE_URL="${DATABASE_URL:-jdbc:postgresql://localhost:5432/rinklnote}"
DB_USER="${DATABASE_USER:-${DB_USER:-rinklnote}}"
DB_PASSWORD="${DB_PASSWORD:-rinklnote}"
H2_JAR="${H2_JAR:-}"
PG_CONTAINER="${PG_CONTAINER:-}"
STAMP="$(date +%Y%m%d_%H%M%S)"

log() { echo "[backup $(date '+%F %T')] $*"; }
die() { echo "[backup $(date '+%F %T')] ERROR: $*" >&2; exit 1; }

mkdir -p "$BACKUP_DIR"

# ---- 判定后端模式 ----
MODE="${BACKUP_MODE:-}"
if [ -z "$MODE" ]; then
  case "$DATABASE_URL" in
    jdbc:h2:file:*) MODE="h2" ;;
    jdbc:postgresql:*) MODE="postgres" ;;
    *) MODE="file" ;;
  esac
fi
log "备份模式: $MODE  输出目录: $BACKUP_DIR  保留: $RETAIN 份"

# ---- 轮转：按前缀保留最新 RETAIN 份，其余删除 ----
rotate() {
  local prefix="$1"
  # ls -1t 按修改时间倒序列出匹配文件，跳过前 RETAIN 个后全删
  ls -1t "$BACKUP_DIR"/"$prefix"_* 2>/dev/null | tail -n +$((RETAIN + 1)) | while read -r old; do
    log "轮转删除过期备份: $old"
    rm -f -- "$old"
  done
}

# ---- 各模式执行 ----
case "$MODE" in
  h2)
    DB_FILE="${DATABASE_URL#jdbc:h2:file:}"
    DB_FILE="${DB_FILE%%;*}"                     # 去掉 ;DB_CLOSE_DELAY=-1 等参数
    [ -n "$H2_JAR" ] || H2_JAR="$(find ~/.gradle "$APP_DIR" -name 'h2-*.jar' 2>/dev/null | sort | tail -n 1 || true)"
    [ -n "$H2_JAR" ] && [ -f "$H2_JAR" ] || die "H2 模式需要 h2*.jar：请设置 H2_JAR 环境变量"
    java -cp "$H2_JAR" org.h2.tools.Backup \
      -url "jdbc:h2:file:$DB_FILE" \
      -file "$BACKUP_DIR/rinklnote_h2_$STAMP.zip" \
      >/dev/null
    log "H2 热备完成: rinklnote_h2_$STAMP.zip"
    rotate "rinklnote_h2"
    ;;

  postgres)
    # 从 JDBC URL 解析 host/port/db（解析失败直接报错，绝不静默备份错库）
    REGEX='jdbc:postgresql://([^:/]+):?([0-9]*)/([^?]+)'
    [[ "$DATABASE_URL" =~ $REGEX ]] || die "无法从 DATABASE_URL 解析 PG 连接信息: $DATABASE_URL"
    PGHOST="${BASH_REMATCH[1]}"
    PGPORT="${BASH_REMATCH[2]:-5432}"
    PGDATABASE="${BASH_REMATCH[3]}"
    OUT="$BACKUP_DIR/rinklnote_pg_$STAMP.dump"
    if [ -n "$PG_CONTAINER" ]; then
      docker exec -e PGPASSWORD="$DB_PASSWORD" "$PG_CONTAINER" \
        pg_dump -U "$DB_USER" -d "$PGDATABASE" -Fc -f "/tmp/rinklnote_pg_$STAMP.dump"
      docker cp "$PG_CONTAINER:/tmp/rinklnote_pg_$STAMP.dump" "$OUT"
      docker exec "$PG_CONTAINER" rm -f "/tmp/rinklnote_pg_$STAMP.dump"
    else
      PGPASSWORD="$DB_PASSWORD" pg_dump -h "$PGHOST" -p "$PGPORT" -U "$DB_USER" -d "$PGDATABASE" -Fc -f "$OUT"
    fi
    [ -s "$OUT" ] || die "pg_dump 产出为空，疑似失败"
    log "PG 备份完成: $(basename "$OUT")"
    rotate "rinklnote_pg"
    ;;

  file)
    DATA_DIR="${DATA_DIR:-$APP_DIR/data}"
    [ -d "$DATA_DIR" ] || die "file 模式需要 DATA_DIR 指向数据目录（当前: $DATA_DIR）"
    OUT="$BACKUP_DIR/rinklnote_file_$STAMP.tar.gz"
    tar -czf "$OUT" -C "$(dirname "$DATA_DIR")" "$(basename "$DATA_DIR")"
    log "文件快照完成: $(basename "$OUT")"
    rotate "rinklnote_file"
    ;;

  *)
    die "未知 BACKUP_MODE: $MODE（可选 h2 | postgres | file）"
    ;;
esac

# ---- 落一份最近备份清单，方便巡检 ----
ls -1t "$BACKUP_DIR" | head -n "$RETAIN" > "$BACKUP_DIR/LATEST.txt"
log "备份成功。当前保留文件见 $BACKUP_DIR/LATEST.txt"
