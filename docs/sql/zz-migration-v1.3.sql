-- ============================================================
-- Mall-Learning v1.2 -> v1.3 迁移脚本（可重复执行）
-- 变更内容：
--   1. mall_order.order_item 新增 image_url 快照列
--      （修复：订单详情图片与商品详情不一致——此前前端只能按
--        productId 拼随机占位图，与商品真实 image_url 无关）
--   2. 按 product_id 关联 mall_product.product 回填存量明细的图片
-- 使用方式：
--   全新环境：docker compose 初始化 SQL 按文件名字母序执行，
--             本文件以 zz- 前缀保证最后运行，无需手动处理。
--   旧数据卷：docker exec -i <mysql容器> mysql -uroot -proot < docs/sql/zz-migration-v1.3.sql
-- ============================================================
SET NAMES utf8mb4;

USE mall_order;

SET @ddl = IF (
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = 'mall_order' AND TABLE_NAME = 'order_item' AND COLUMN_NAME = 'image_url') = 0,
  'ALTER TABLE order_item ADD COLUMN image_url VARCHAR(255) NULL AFTER count',
  'SELECT ''[skip] order_item.image_url already exists'' AS note'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 回填存量订单项的图片快照（仅补空值，可重复执行）
UPDATE order_item oi
JOIN mall_product.product p ON p.id = oi.product_id
SET oi.image_url = p.image_url
WHERE oi.image_url IS NULL;
