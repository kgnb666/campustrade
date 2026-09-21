#!/usr/bin/env bash
# ==============================================================================
#  生成 /etc/campustrade/prod.env（生产变量文件，凭据全部随机生成）
#
#  用法（需要 root）：
#      sudo bash deploy/init-prod-env.sh <对外访问地址> [边缘端口]
#
#  示例：
#      sudo bash deploy/init-prod-env.sh 129.204.61.68 8080
#      sudo EDGE_PORT=8080 MAIL_HOST=smtp.exmail.qq.com MAIL_USER=noreply@x.com \
#           MAIL_PASS='授权码' bash deploy/init-prod-env.sh app.example.com
#
#  可用环境变量：
#      EDGE_PORT   边缘 Nginx 发布的宿主端口（默认 8080）
#      EDGE_BIND   监听地址（默认 0.0.0.0；只给本机访问用 127.0.0.1）
#      WEB_ROOT    前端产物目录（默认 /opt/campustrade/web）
#      MAIL_HOST / MAIL_PORT / MAIL_USER / MAIL_PASS / MAIL_FROM
#                  SMTP 配置；未提供 MAIL_USER 时写入占位值（服务能启动，
#                  但校园认证验证码发不出去，上线前必须替换）
#      FORCE=1     已存在 prod.env 时允许覆盖
#
#  生成的文件权限为 600、属主 root:root，且只打印变量名不打印口令值。
# ==============================================================================
set -euo pipefail

PUBLIC_HOST="${1:-}"
EDGE_PORT="${2:-${EDGE_PORT:-8080}}"
EDGE_BIND="${EDGE_BIND:-0.0.0.0}"
WEB_ROOT="${WEB_ROOT:-/opt/campustrade/web}"
MAIL_HOST="${MAIL_HOST:-smtp.exmail.qq.com}"
MAIL_PORT="${MAIL_PORT:-465}"
MAIL_USER="${MAIL_USER:-}"
MAIL_PASS="${MAIL_PASS:-}"
MAIL_FROM="${MAIL_FROM:-}"

ENV_DIR=/etc/campustrade
ENV_FILE="$ENV_DIR/prod.env"

if [ -z "$PUBLIC_HOST" ]; then
  echo "用法: sudo bash deploy/init-prod-env.sh <对外访问地址> [边缘端口]" >&2
  exit 2
fi

if [ "$(id -u)" -ne 0 ]; then
  echo "需要 root：请用 sudo bash $0 ..." >&2
  exit 2
fi

if [ -f "$ENV_FILE" ] && [ "${FORCE:-0}" != "1" ]; then
  echo "已存在 $ENV_FILE（拒绝覆盖，避免把可用凭据冲掉）。要覆盖请加 FORCE=1。" >&2
  exit 3
fi

# 未提供发信账号时写入"非空但不可用"的占位值：
# 目的是让 prod 启动守卫通过（守卫只拒绝空值与 CHANGE_ME 前缀），
# 同时用醒目的字面量提醒运维这里还没配置真实 SMTP。
if [ -z "$MAIL_USER" ]; then
  MAIL_USER="SET_ME_REAL_SMTP_ACCOUNT@example.invalid"
  MAIL_PASS="SET_ME_REAL_SMTP_AUTH_CODE"
  echo "提示：未提供 MAIL_USER，已写入 SMTP 占位值 —— 校园认证邮件发不出去。" >&2
fi
[ -n "$MAIL_FROM" ] || MAIL_FROM="$MAIL_USER"

PUBLIC_BASE="http://$PUBLIC_HOST:$EDGE_PORT"

POSTGRES_PASSWORD="$(openssl rand -hex 24)"
REDIS_PASSWORD="$(openssl rand -hex 24)"
MINIO_ROOT_USER="ctadmin$(openssl rand -hex 4)"
MINIO_ROOT_PASSWORD="$(openssl rand -hex 24)"
JWT_SECRET="$(openssl rand -hex 32)"

mkdir -p "$ENV_DIR"
umask 077
cat > "$ENV_FILE" <<EOF
# 由 deploy/init-prod-env.sh 生成于 $(date -Iseconds)
# 凭据为随机值，请连同备份一起妥善保管；不要提交进仓库。

# 1. 对外访问地址
EDGE_PORT=$EDGE_PORT
EDGE_BIND=$EDGE_BIND
WEB_ROOT=$WEB_ROOT

# 2. 中间件与应用凭据
POSTGRES_DB=campustrade
POSTGRES_USER=campustrade
POSTGRES_PASSWORD=$POSTGRES_PASSWORD

REDIS_PASSWORD=$REDIS_PASSWORD

MINIO_ROOT_USER=$MINIO_ROOT_USER
MINIO_ROOT_PASSWORD=$MINIO_ROOT_PASSWORD
MINIO_BUCKET_NAME=campustrade
MINIO_URL_PREFIX=$PUBLIC_BASE/img/campustrade

JWT_SECRET=$JWT_SECRET

CORS_ALLOWED_ORIGINS=$PUBLIC_BASE

# 3. 校园认证邮件（TODO：替换成真实 SMTP 账号与授权码后重启）
MAIL_HOST=$MAIL_HOST
MAIL_PORT=$MAIL_PORT
MAIL_USERNAME=$MAIL_USER
MAIL_PASSWORD=$MAIL_PASS
MAIL_FROM=$MAIL_FROM
MAIL_SSL_ENABLED=true

# 4. 反向代理：边缘 Nginx 与后端同处 compose 网络，故填网桥网段
SECURITY_TRUSTED_PROXIES=172.16.0.0/12

# 5. 镜像 tag（= 后端 pom 版本号去掉 -SNAPSHOT）
APP_IMAGE_TAG=0.0.1
EOF

chown root:root "$ENV_FILE"
chmod 600 "$ENV_FILE"

echo "已生成 $ENV_FILE"
echo "对外地址: $PUBLIC_BASE"
echo "邮件账号: $MAIL_USER"
echo "变量清单:"
sed -n 's/^\([A-Z_]*\)=.*/  \1/p' "$ENV_FILE"
