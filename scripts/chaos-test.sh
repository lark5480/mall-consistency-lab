#!/usr/bin/env bash
# 混沌测试：Saga 中途杀掉 order-service，验证对账任务把系统拉回一致。
#
# 前置：完整栈已 docker compose up -d（mysql/redis/nacos/gateway/user/product/order 全 healthy）
# 用法：bash scripts/chaos-test.sh [ROUNDS] [ORDERS_PER_ROUND]
#   ROUNDS           杀服务轮数（默认 3）
#   ORDERS_PER_ROUND 每轮下单请求数（默认 12）
# 不变式（全部基于增量，不要求干净库）：
#   I1 库存台账：两次快照间 product.stock 变化 == -Δ(DEDUCTED 行 count 合计)
#   I2 无孤儿扣减：宽限期后不存在 status=DEDUCTED 且（订单不存在或已 CANCELLED）的去重行
#   I3 下单闭环：脚本确认成功的每个 orderNo 必有去重行；PENDING 订单对应行必为 DEDUCTED
# 说明：压测期间被杀服务时点的请求属"结果未知"流量，成败不作断言，只断言最终一致性。
set -uo pipefail

ROUNDS="${1:-3}"
PER_ROUND="${2:-12}"
GATEWAY="${GATEWAY:-http://localhost:8080}"
cd "$(dirname "$0")/.." || exit 1   # compose / 容器名均按仓库根解析

MYSQL="docker exec mall-consistency-lab-mysql-1 mysql -uroot -proot --default-character-set=utf8mb4 -N -s -e"
DC="docker compose"

log() { printf '[chaos %s] %s\n' "$(date +%H:%M:%S)" "$*"; }
die() { printf '[chaos FAIL] %s\n' "$*" >&2; exit 1; }
sql() { $MYSQL "$1" 2>/dev/null; }

wait_healthy() { # $1=service $2=timeout_sec
  local svc="$1" deadline=$((SECONDS + ${2:-120}))
  while (( SECONDS < deadline )); do
    [ "$(docker inspect --format '{{.State.Health.Status}}' "mall-consistency-lab-${svc}-1" 2>/dev/null)" = "healthy" ] && return 0
    sleep 3
  done
  die "service ${svc} not healthy in ${2:-120}s"
}

snapshot() { # 单行输出：product1库存 product2库存 DEDUCTED行count合计
  sql "SELECT (SELECT stock FROM mall_product.product WHERE id=1), \
              (SELECT stock FROM mall_product.product WHERE id=2), \
              (SELECT COALESCE(SUM(\`count\`),0) FROM mall_product.stock_dedup_log WHERE status='DEDUCTED');"
}

reconcile_params() { # 从运行中的 product-service 读实际对账参数（脚本不需重复 export）
  local stale interval_ms
  stale=$(docker exec mall-consistency-lab-product-service-1 printenv RECONCILE_STALE_MINUTES 2>/dev/null || echo 10)
  interval_ms=$(docker exec mall-consistency-lab-product-service-1 printenv RECONCILE_INTERVAL_MS 2>/dev/null || echo 300000)
  echo "$stale $(( interval_ms / 1000 + 5 ))"
}

login() {
  TOKEN=$(curl -s -X POST "$GATEWAY/api/v1/auth/login" -H 'Content-Type: application/json' \
    -d '{"username":"demo","password":"123456"}' | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
  [ -n "${TOKEN:-}" ] || die "login failed"
}

order_one() { # 成功 echo orderNo；失败/未知结果（宕机窗口、409 等）echo 空
  local pid=1; [ $((RANDOM % 10)) -ge 7 ] && pid=2   # 70% 下 product1，30% 尝试低库存的 product2
  curl -s --max-time 5 -X POST "$GATEWAY/api/v1/orders" \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -d "{\"productId\":$pid,\"count\":$((RANDOM % 3 + 1)),\"addressId\":1}" \
    | sed -n 's/.*"orderNo":"\([^"]*\)".*/\1/p'
}

act_on_order() { # 随机支付/取消，制造回补与状态机并发流量
  local no="$1" r=$((RANDOM % 4)) path
  [ -n "$no" ] || return 0
  case $r in
    0) path="/pay" ;;
    1) path="/cancel" ;;
    *) return 0 ;;
  esac
  curl -s --max-time 5 -X POST "$GATEWAY/api/v1/orders/$no$path" \
    -H "Authorization: Bearer $TOKEN" >/dev/null
}

killer() { # 后台随机延迟 2~9s 后杀 order-service 并拉起等待 healthy
  sleep $((RANDOM % 8 + 2))
  log "KILL order-service (round $1)"
  docker kill mall-consistency-lab-order-service-1 >/dev/null 2>&1
  $DC up -d order-service >/dev/null 2>&1
  wait_healthy order-service 150
  log "order-service recovered (round $1)"
}

# ---------------- 主流程 ----------------
command -v docker >/dev/null || die "docker required"
login
for svc in mysql product-service order-service gateway; do wait_healthy "$svc" 60; done
read -r STALE_MIN INTERVAL_S <<<"$(reconcile_params)"
log "stack healthy; token OK; rounds=$ROUNDS orders/round=$PER_ROUND; reconcile stale=${STALE_MIN}min interval=${INTERVAL_S}s"

# T0 基线快照：必须在制造流量之前
S0LINE=$(sql "SELECT (SELECT stock FROM mall_product.product WHERE id=1), \
                     (SELECT stock FROM mall_product.product WHERE id=2), \
                     (SELECT COALESCE(SUM(\`count\`),0) FROM mall_product.stock_dedup_log WHERE status='DEDUCTED');")
read -r S1_0 S2_0 D_0 <<<"$S0LINE"
[ -n "$S1_0" ] || die "baseline snapshot failed"

declare -a CONFIRMED=()

for r in $(seq 1 "$ROUNDS"); do
  killer "$r" & KPID=$!
  for i in $(seq 1 "$PER_ROUND"); do
    no=$(order_one) || true
    if [ -n "$no" ]; then CONFIRMED+=("$no"); act_on_order "$no"; fi
    sleep 0.4
  done
  wait "$KPID" || die "killer subshell failed on round $r"
  log "round $r done: confirmed_orders=${#CONFIRMED[@]}"
done

# 宽限期：stale 窗口 + 2 轮扫描 + 30s 缓冲；期间无新流量，宽限期后可对全部 DEDUCTED 行做全量检查（规避时钟偏移问题）
log "grace period: ${STALE_MIN}min + 2x${INTERVAL_S}s + 30s"
sleep $(( STALE_MIN * 60 + INTERVAL_S * 2 + 30 ))
wait_healthy product-service 60; wait_healthy order-service 60

S1LINE=$(sql "SELECT (SELECT stock FROM mall_product.product WHERE id=1), \
                     (SELECT stock FROM mall_product.product WHERE id=2), \
                     (SELECT COALESCE(SUM(\`count\`),0) FROM mall_product.stock_dedup_log WHERE status='DEDUCTED');")
read -r S1_1 S2_1 D_1 <<<"$S1LINE"
[ -n "$S1_1" ] || die "final snapshot failed"

FAIL=0
# I1 台账
ds=$(( (S1_1 + S2_1) - (S1_0 + S2_0) ))
dd=$(( D_0 - D_1 ))
if [ "$ds" -ne "$dd" ]; then
  echo "I1 FAIL: stock delta=$ds, expected=$dd (stock T0=[$S1_0,$S2_0] T1=[$S1_1,$S2_1], DEDUCTED-sum T0=$D_0 T1=$D_1)"
  FAIL=1
else
  echo "I1 PASS: stock delta=$ds == -Δ(DEDUCTED sum)"
fi

# I2 无孤儿扣减（跨库 join：mall_product.stock_dedup_log ↔ mall_order.order）
ORPHANS=$(sql "SELECT d.order_no FROM mall_product.stock_dedup_log d \
  LEFT JOIN mall_order.\`order\` o ON o.order_no=d.order_no \
  WHERE d.status='DEDUCTED' AND (o.order_no IS NULL OR o.status='CANCELLED');")
if [ -n "$ORPHANS" ]; then
  echo "I2 FAIL: orphan DEDUCTED rows:"; echo "$ORPHANS"; FAIL=1
else
  echo "I2 PASS: no orphan DEDUCTED rows"
fi

# I3 确认成功的订单闭环
for no in "${CONFIRMED[@]}"; do
  row=$(sql "SELECT d.status, IFNULL(o.status,'<missing>') FROM mall_product.stock_dedup_log d \
    LEFT JOIN mall_order.\`order\` o ON o.order_no=d.order_no WHERE d.order_no='$no';")
  if [ -z "$row" ]; then
    echo "I3 FAIL: confirmed order $no has no dedup row"; FAIL=1
  else
    dstatus=${row%%	*}; ostatus=${row##*	}
    if [ "$ostatus" = "PENDING" ] && [ "$dstatus" != "DEDUCTED" ]; then
      echo "I3 FAIL: order $no is PENDING but dedup=$dstatus"; FAIL=1
    fi
  fi
done
[ "$FAIL" -eq 0 ] && echo "I3 PASS: ${#CONFIRMED[@]} confirmed orders all closed-loop"

echo
if [ "$FAIL" -eq 0 ]; then
  log "ALL INVARIANTS PASS ($ROUNDS kills, $((ROUNDS*PER_ROUND)) requests, ${#CONFIRMED[@]} confirmed orders)"
else
  die "INVARIANT VIOLATION — check mall-product logs (OrphanDeductionReconcileJob) before re-run"
fi
