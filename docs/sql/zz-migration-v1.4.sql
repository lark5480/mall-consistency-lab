-- ============================================================
-- Mall-Learning v1.3 -> v1.4 幂等迁移脚本（可重复执行）
-- 变更内容：补齐高频查询路径缺失的索引
--   1. mall_order.order             + KEY idx_order_created (created_at)
--      （管理端订单列表按时间排序、统计接口的 created_at 范围聚合）
--   2. mall_order.order             + KEY idx_order_status (status)
--      （管理端按状态过滤、pendingShipCount 计数）
--   3. mall_order.order             + KEY idx_order_status_updated (status, updated_at)
--      （超时自动确认收货任务扫描 SHIPPED 且 updated_at 早于截止时间）
--   4. mall_product.stock_dedup_log + KEY idx_dedup_status_created (status, created_at)
--      （对账任务周期扫描 DEDUCTED 且早于 stale 阈值的记录）
-- 使用方式：
--   全新环境：docker compose 初始化 SQL 按文件名字母序执行，
--             本文件以 zz- 前缀保证最后运行，无需手动处理。
--   旧数据卷：docker exec -i <mysql容器> mysql -uroot -proot < docs/sql/zz-migration-v1.4.sql
-- ============================================================
SET NAMES utf8mb4;

-- ---------- mall_order.order ----------
USE mall_order;

SET @ddl = IF (
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = 'mall_order' AND TABLE_NAME = 'order' AND INDEX_NAME = 'idx_order_created') = 0,
  'ALTER TABLE `order` ADD KEY idx_order_created (created_at)',
  'SELECT ''[skip] mall_order.order.idx_order_created already exists'' AS note'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF (
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = 'mall_order' AND TABLE_NAME = 'order' AND INDEX_NAME = 'idx_order_status') = 0,
  'ALTER TABLE `order` ADD KEY idx_order_status (status)',
  'SELECT ''[skip] mall_order.order.idx_order_status already exists'' AS note'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF (
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = 'mall_order' AND TABLE_NAME = 'order' AND INDEX_NAME = 'idx_order_status_updated') = 0,
  'ALTER TABLE `order` ADD KEY idx_order_status_updated (status, updated_at)',
  'SELECT ''[skip] mall_order.order.idx_order_status_updated already exists'' AS note'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- mall_product.stock_dedup_log ----------
USE mall_product;

SET @ddl = IF (
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = 'mall_product' AND TABLE_NAME = 'stock_dedup_log' AND INDEX_NAME = 'idx_dedup_status_created') = 0,
  'ALTER TABLE stock_dedup_log ADD KEY idx_dedup_status_created (status, created_at)',
  'SELECT ''[skip] mall_product.stock_dedup_log.idx_dedup_status_created already exists'' AS note'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
