# chaos-test.ps1 — mall-consistency-lab 混沌测试（Windows / PowerShell 原生版）
#
# 为什么有这一版：chaos-test.sh 需要 bash + 一个能访问 docker daemon 的环境。在
# 「Rancher Desktop + WSL2」这类本机配置下，WSL 发行版里没有 /var/run/docker.sock
# （docker 只在 rancher-desktop 发行版内可用），bash 版直接跑不起来。本脚本用 Windows
# 侧的 docker CLI 做同样的事，不经过 WSL。
#
# 与 chaos-test.sh 的关系：三条不变式（I1/I2/I3）完全一致，同为增量口径、不要求干净库；
# 并额外输出三项度量：
#   M1 对账差异率   = 宽限期后仍为孤儿的 DEDUCTED 行数 / 去重行总数
#   M2 补偿成功率   = 已取消订单中 dedup 行已置 RESTORED 的比例
#   M3 故障恢复耗时 = order-service 从 kill 到 healthy 的秒数（每轮，输出平均/最大）
# 结束时会打印一段可直接引用的汇总块。
#
# 前置：完整栈已 `docker compose up -d`，且 7 个容器全部 healthy
# 用法：pwsh -File scripts/chaos-test.ps1 [-Rounds 3] [-PerRound 12] [-RepoRoot <path>]
#   冒烟（宽限期压到 20s、约 1 分钟，结果不可用于度量）：
#     pwsh -File scripts/chaos-test.ps1 -Rounds 1 -PerRound 3 -GraceSecOverride 20
#
# 实测（2026-10-06，8 核 32G / Rancher Desktop，默认对账参数，总耗时 22.1 分钟）：
#   3 轮 kill / 36 次下单流量；I1/I2/I3 全 PASS；M1 对账差异率 0 %（孤儿 0 / 去重行 33）；
#   M2 补偿成功率 100 %（2/2）；M3 恢复耗时 平均 24.6 s / 最大 36.3 s；17 单确认订单 100 % 闭环。
#   bash 版 2026-09-03 的运行记录见 docs/ACCEPTANCE.md。
#
# 不变式（全部基于增量，不要求干净库）：
#   I1 库存台账：两次快照间 product.stock 变化 == -Δ(DEDUCTED 行 count 合计)
#   I2 无孤儿扣减：宽限期后不存在 status=DEDUCTED 且（订单不存在或已 CANCELLED）的去重行
#   I3 下单闭环：脚本确认成功的每个 orderNo 必有去重行；PENDING 订单对应行必为 DEDUCTED
# 说明：压测期间被杀服务时点的请求属"结果未知"流量，成败不作断言，只断言最终一致性。
#
# 宽限期 = RECONCILE_STALE_MINUTES + 2 × RECONCILE_INTERVAL_MS + 30s（默认约 1240 s）。
# 这是「孤儿扣减多久之后才该被对账捞到」的业务窗口，不要为了跑得快而改小——改小等于放宽断言。

[CmdletBinding()]
param(
    [int]$Rounds = 3,
    [int]$PerRound = 12,
    [string]$Gateway = 'http://localhost:8080',
    [string]$RepoRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$Project = 'mall-consistency-lab',
    [int]$GraceSecOverride = 0   # 0 = 用运行中对账参数计算；>0 仅用于冒烟验证，正式度量不要传
)

$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'

function Log  { param($m) Write-Host ("[chaos {0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $m) }
function Fail { param($m) Write-Host "[chaos FAIL] $m" -ForegroundColor Red; exit 1 }

function Wait-Healthy {
    param([string]$Svc, [int]$TimeoutSec = 120)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    $name = "$Project-$Svc-1"
    while ((Get-Date) -lt $deadline) {
        $st = (& docker inspect --format '{{.State.Health.Status}}' $name 2>$null)
        if ($st -eq 'healthy') { return $true }
        Start-Sleep -Seconds 3
    }
    return $false
}

# ---- SQL：-N -s 输出制表符分隔、无表头 ----
function Sql {
    param([string]$Query)
    $out = & docker exec "$Project-mysql-1" mysql -uroot -proot --default-character-set=utf8mb4 -N -s -e $Query 2>$null
    return ($out | Out-String).Trim()
}

function Get-Snapshot {
    # 单行三列：product1库存 <tab> product2库存 <tab> DEDUCTED行count合计
    $q = 'SELECT (SELECT stock FROM mall_product.product WHERE id=1), ' +
         '(SELECT stock FROM mall_product.product WHERE id=2), ' +
         "(SELECT COALESCE(SUM(count),0) FROM mall_product.stock_dedup_log WHERE status='DEDUCTED');"
    $line = Sql $q
    if (-not $line) { return $null }
    $p = $line -split "`t"
    return [pscustomobject]@{ S1 = [int]$p[0]; S2 = [int]$p[1]; D = [int]$p[2] }
}

function Get-ReconcileParams {
    $stale = (& docker exec "$Project-product-service-1" printenv RECONCILE_STALE_MINUTES 2>$null)
    $ms    = (& docker exec "$Project-product-service-1" printenv RECONCILE_INTERVAL_MS 2>$null)
    if (-not $stale) { $stale = '10' }
    if (-not $ms)    { $ms = '300000' }
    return [pscustomobject]@{ StaleMin = [int]$stale; IntervalSec = ([int]$ms / 1000) + 5 }
}

function Connect-Token {
    $body = '{"username":"demo","password":"123456"}'
    try {
        $r = Invoke-RestMethod -Method Post -Uri "$Gateway/api/v1/auth/login" -ContentType 'application/json' -Body $body -TimeoutSec 10
    } catch { Fail "login request failed: $($_.Exception.Message)" }
    $tok = $r.data.token; if (-not $tok) { $tok = $r.token }
    if (-not $tok) { Fail "login succeeded but token not found in response: $($r | ConvertTo-Json -Compress)" }
    return $tok
}

function New-Order {
    param([string]$Token)
    $pid_ = 1; if ((Get-Random -Minimum 0 -Maximum 10) -ge 7) { $pid_ = 2 }
    $cnt = (Get-Random -Minimum 1 -Maximum 4)
    $body = @{ productId = $pid_; count = $cnt; addressId = 1 } | ConvertTo-Json -Compress
    try {
        $r = Invoke-RestMethod -Method Post -Uri "$Gateway/api/v1/orders" -TimeoutSec 5 `
             -Headers @{ Authorization = "Bearer $Token" } -ContentType 'application/json' -Body $body
        $no = $r.data.orderNo; if (-not $no) { $no = $r.orderNo }
        return $no
    } catch { return $null }   # 宕机窗口 / 409 / 库存不足 → 结果未知，不计入确认集合
}

function Invoke-ActOnOrder {
    param([string]$Token, [string]$OrderNo)
    if (-not $OrderNo) { return }
    $r = Get-Random -Minimum 0 -Maximum 4
    $path = switch ($r) { 0 { '/pay' } 1 { '/cancel' } default { return } }
    try {
        Invoke-RestMethod -Method Post -Uri "$Gateway/api/v1/orders/$OrderNo$path" -TimeoutSec 5 `
            -Headers @{ Authorization = "Bearer $Token" } | Out-Null
    } catch { }
}

# ---- 主流程 ----
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { Fail 'docker required' }
Set-Location $RepoRoot

foreach ($svc in @('mysql', 'product-service', 'order-service', 'gateway')) {
    if (-not (Wait-Healthy -Svc $svc -TimeoutSec 60)) { Fail "service $svc not healthy in 60s" }
}

$token = Connect-Token
$rp = Get-ReconcileParams
$graceSec = $rp.StaleMin * 60 + $rp.IntervalSec * 2 + 30
if ($GraceSecOverride -gt 0) { $graceSec = $GraceSecOverride; Log "WARN: grace period overridden to ${graceSec}s (smoke mode) — 不要把该次结果写进简历" }
Log "stack healthy; token OK; rounds=$Rounds orders/round=$PerRound; reconcile stale=$($rp.StaleMin)min interval=$($rp.IntervalSec)s; grace=${graceSec}s"

$S0 = Get-Snapshot
if (-not $S0) { Fail 'baseline snapshot failed' }

# 后台 killer：随机 2~8s 后 kill order-service，等待 healthy，并记录恢复耗时
$killerScript = Join-Path $env:TEMP ("chaos-killer-{0}.ps1" -f ([guid]::NewGuid().ToString('N')))
@'
param([string]$Project, [string]$RepoRoot, [int]$Round, [int]$DelaySec)
$sw = [System.Diagnostics.Stopwatch]::StartNew()
Start-Sleep -Seconds $DelaySec
Write-Host ("[chaos {0}] KILL order-service (round {1})" -f (Get-Date -Format 'HH:mm:ss'), $Round)
& docker kill "$Project-order-service-1" | Out-Null
& docker compose -f (Join-Path $RepoRoot 'docker-compose.yml') --project-directory $RepoRoot up -d order-service 2>&1 | Out-Null
$deadline = (Get-Date).AddSeconds(150)
$ok = $false
while ((Get-Date) -lt $deadline) {
    $st = (& docker inspect --format '{{.State.Health.Status}}' "$Project-order-service-1" 2>$null)
    if ($st -eq 'healthy') { $ok = $true; break }
    Start-Sleep -Seconds 2
}
$sw.Stop()
Write-Host ("[chaos {0}] order-service recovered={1} in {2:N1}s (round {3})" -f (Get-Date -Format 'HH:mm:ss'), $ok, $sw.Elapsed.TotalSeconds, $Round)
'@ | Set-Content -Path $killerScript -Encoding UTF8

$confirmed = New-Object System.Collections.Generic.List[string]
$recoveries = New-Object System.Collections.Generic.List[double]
$roundLog = @()

for ($r = 1; $r -le $Rounds; $r++) {
    $delay = Get-Random -Minimum 2 -Maximum 10
    $kp = Start-Process -FilePath 'pwsh' -ArgumentList @(
        '-NoProfile', '-File', $killerScript, '-Project', $Project, '-RepoRoot', $RepoRoot, '-Round', $r, '-DelaySec', $delay
    ) -NoNewWindow -PassThru -RedirectStandardOutput (Join-Path $env:TEMP "chaos-killer-$r.log")

    for ($i = 1; $i -le $PerRound; $i++) {
        $no = New-Order -Token $token
        if ($no) { $confirmed.Add($no); Invoke-ActOnOrder -Token $token -OrderNo $no }
        Start-Sleep -Milliseconds 400
    }

    $kp.WaitForExit()
    $klog = Get-Content (Join-Path $env:TEMP "chaos-killer-$r.log") -Raw -ErrorAction SilentlyContinue
    if ($klog -match 'in ([0-9.]+)s') { $recoveries.Add([double]$Matches[1]) }
    $klogLine = ''
    if ($klog) { $klogLine = ($klog -replace "`r?`n", ' | ').Trim() }
    $roundLog += ("round {0}: confirmed={1}  {2}" -f $r, $confirmed.Count, $klogLine)
    Log "round $r done: confirmed_orders=$($confirmed.Count)"
    if (-not (Wait-Healthy -Svc 'order-service' -TimeoutSec 150)) { Fail "order-service not healthy after round $r" }
}

Log "grace period: ${graceSec}s (computed: stale $($rp.StaleMin)min + 2x$($rp.IntervalSec)s + 30s)"
Start-Sleep -Seconds $graceSec
if (-not (Wait-Healthy -Svc 'product-service' -TimeoutSec 60)) { Fail 'product-service unhealthy after grace' }
if (-not (Wait-Healthy -Svc 'order-service'  -TimeoutSec 60)) { Fail 'order-service unhealthy after grace' }

$S1 = Get-Snapshot
if (-not $S1) { Fail 'final snapshot failed' }

$failed = $false

# ---- I1 台账守恒 ----
$ds = ($S1.S1 + $S1.S2) - ($S0.S1 + $S0.S2)
$dd = $S0.D - $S1.D
if ($ds -ne $dd) {
    Write-Host "I1 FAIL: stock delta=$ds, expected=$dd (stock T0=[$($S0.S1),$($S0.S2)] T1=[$($S1.S1),$($S1.S2)], DEDUCTED-sum T0=$($S0.D) T1=$($S1.D))" -ForegroundColor Red
    $failed = $true
} else {
    Write-Host "I1 PASS: stock delta=$ds == -Δ(DEDUCTED sum)" -ForegroundColor Green
}

# ---- I2 无孤儿扣减 + M1 对账差异率 ----
$orphanQ = 'SELECT d.order_no FROM mall_product.stock_dedup_log d ' +
           'LEFT JOIN mall_order.`order` o ON o.order_no=d.order_no ' +
           "WHERE d.status='DEDUCTED' AND (o.order_no IS NULL OR o.status='CANCELLED');"
$orphans = Sql $orphanQ
$orphanCount = if ($orphans) { ($orphans -split "`n").Count } else { 0 }
$totalRows = [int](Sql 'SELECT COUNT(*) FROM mall_product.stock_dedup_log;')
$differRate = if ($totalRows -gt 0) { [math]::Round(100.0 * $orphanCount / $totalRows, 4) } else { 0 }
if ($orphanCount -gt 0) {
    Write-Host "I2 FAIL: orphan DEDUCTED rows:" -ForegroundColor Red; Write-Host $orphans
    $failed = $true
} else {
    Write-Host "I2 PASS: no orphan DEDUCTED rows" -ForegroundColor Green
}

# ---- I3 确认订单闭环 ----
$notClosed = 0
foreach ($no in $confirmed) {
    $row = Sql "SELECT d.status, IFNULL(o.status,'<missing>') FROM mall_product.stock_dedup_log d LEFT JOIN mall_order.``order`` o ON o.order_no=d.order_no WHERE d.order_no='$no';"
    if (-not $row) { Write-Host "I3 FAIL: confirmed order $no has no dedup row" -ForegroundColor Red; $failed = $true; $notClosed++; continue }
    $parts = $row -split "`t"
    if ($parts[1] -eq 'PENDING' -and $parts[0] -ne 'DEDUCTED') {
        Write-Host "I3 FAIL: order $no is PENDING but dedup=$($parts[0])" -ForegroundColor Red; $failed = $true; $notClosed++
    }
}
$closedLoopRate = if ($confirmed.Count -gt 0) { [math]::Round(100.0 * ($confirmed.Count - $notClosed) / $confirmed.Count, 2) } else { 0 }
if ($notClosed -eq 0) { Write-Host "I3 PASS: $($confirmed.Count) confirmed orders all closed-loop" -ForegroundColor Green }

# ---- M2 补偿成功率：已取消订单的 dedup 行是否都置为 RESTORED ----
$cancelQ = 'SELECT COUNT(*) FROM mall_order.`order` o JOIN mall_product.stock_dedup_log d ON d.order_no=o.order_no WHERE o.status=''CANCELLED'';'
$cancelTotal = [int](Sql $cancelQ)
$cancelRestored = [int](Sql 'SELECT COUNT(*) FROM mall_order.`order` o JOIN mall_product.stock_dedup_log d ON d.order_no=o.order_no WHERE o.status=''CANCELLED'' AND d.status=''RESTORED'';')
$compensateRate = if ($cancelTotal -gt 0) { [math]::Round(100.0 * $cancelRestored / $cancelTotal, 2) } else { $null }

# ---- M3 故障恢复耗时 ----
$recAvg = if ($recoveries.Count -gt 0) { [math]::Round(($recoveries | Measure-Object -Average).Average, 1) } else { $null }
$recMax = if ($recoveries.Count -gt 0) { [math]::Round(($recoveries | Measure-Object -Maximum).Maximum, 1) } else { $null }

# ---- 汇总 ----
Write-Host ''
Write-Host '==================== 度量汇总（可直接引用） ====================' -ForegroundColor Cyan
Write-Host "故障注入         : $Rounds 轮 kill order-service，$($Rounds * $PerRound) 次下单流量"
Write-Host "M3 恢复耗时      : 平均 $recAvg s / 最大 $recMax s（kill → healthy）"
Write-Host "M1 对账差异率    : $differRate %（孤儿 $orphanCount / 去重行 $totalRows）"
Write-Host "M2 补偿成功率    : $(if ($null -eq $compensateRate) { 'n/a（无已取消订单）' } else { "$compensateRate %（$cancelRestored / $cancelTotal 已取消订单已回补）" })"
Write-Host "I3 闭环率        : $closedLoopRate %（确认订单 $($confirmed.Count) 单）"
Write-Host "I1 台账守恒      : stock delta=$ds, -Δ(DEDUCTED)=$dd"
Write-Host '================================================================' -ForegroundColor Cyan
Write-Host ''

Remove-Item $killerScript -Force -ErrorAction SilentlyContinue

if ($failed) { Fail 'INVARIANT VIOLATION — check mall-product logs (OrphanDeductionReconcileJob) before re-run' }
Log "ALL INVARIANTS PASS ($Rounds kills, $($Rounds * $PerRound) requests, $($confirmed.Count) confirmed orders)"
