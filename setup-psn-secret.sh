#!/bin/bash
# 创建/更新 Halo Secret psn-npsso —— 所有敏感值均为隐藏输入，不落盘、不进历史、不打印
set -e
BASE="${BASE:-http://127.0.0.1:8092}"
NAME="psn-npsso"

echo "目标: ${BASE}  Secret名: ${NAME}"
echo "TOKEN 获取: Halo后台 右上角头像 -> API访问令牌 -> 新建令牌(角色选管理员) -> 复制(只显示一次)"
read -rs -p "粘贴 API 访问令牌(TOKEN): " TOKEN; echo
read -rs -p "粘贴 npsso 值(浏览器 playstation.com Cookie): " NPSSO; echo

# 先建占位，再用 PUT 覆盖写入（Halo secrets 支持按名称幂等 upsert）
HTTP=$(curl -s -o /dev/null -w '%{http_code}' -X PUT "${BASE}/apis/api.console.halo.run/v1alpha1/secrets/${NAME}" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer ${TOKEN}" \
  -d "{\"apiVersion\":\"v1\",\"kind\":\"Secret\",\"type\":\"ops.run/psn\",\"metadata\":{\"name\":\"${NAME}\"},\"stringData\":{\"npsso\":\"${NPSSO}\"}}")

if [ "$HTTP" = "200" ] || [ "$HTTP" = "201" ]; then
  echo "OK: Secret ${NAME} 已写入 (HTTP $HTTP)。值已隐藏，不会再次输出。"
else
  echo "失败: HTTP $HTTP（检查令牌是否过期/权限，npsso 是否复制完整）"
fi

# 清环境变量残留
unset TOKEN NPSSO
