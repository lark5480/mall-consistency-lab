-- ============================================================
-- Mall-Learning v1.1 -> v1.2 迁移脚本（可重复执行）
-- 变更内容：
--   1. mall_user 新增 address 收货地址表
--   2. mall_order.order 新增收货人快照列 receiver_name/receiver_phone/receiver_address
--   3. 为 demo 用户种入两条地址（替代 C 端原静态地址数组）
-- 使用方式：
--   全新环境：docker compose 初始化 SQL 按文件名字母序执行，
--             本文件以 zz- 前缀保证最后运行，无需手动处理。
--   旧数据卷：docker exec -i <mysql容器> mysql -uroot -proot < docs/sql/zz-migration-v1.2.sql
-- ============================================================
SET NAMES utf8mb4;

-- ---------- mall_user：address 表 ----------
USE mall_user;

CREATE TABLE IF NOT EXISTS `address` (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id BIGINT UNSIGNED NOT NULL,
  receiver VARCHAR(50) NOT NULL,
  phone VARCHAR(20) NOT NULL,
  province VARCHAR(30) NOT NULL,
  city VARCHAR(30) NOT NULL,
  district VARCHAR(30) NOT NULL,
  detail VARCHAR(200) NOT NULL,
  is_default TINYINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  KEY idx_address_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- demo 种子地址：仅当 demo 名下没有任何地址时插入（幂等）
INSERT INTO address (user_id, receiver, phone, province, city, district, detail, is_default)
SELECT u.id, '张三', '13800000001', '上海市', '上海市', '浦东新区', '学习路 1 号', 1
FROM `user` u
WHERE u.username = 'demo'
  AND NOT EXISTS (SELECT 1 FROM address a WHERE a.user_id = u.id);

INSERT INTO address (user_id, receiver, phone, province, city, district, detail, is_default)
SELECT u.id, '李四', '13800000002', '北京市', '北京市', '海淀区', '实践路 2 号', 0
FROM `user` u
WHERE u.username = 'demo'
  AND NOT EXISTS (
    SELECT 1 FROM address a
    WHERE a.user_id = u.id AND a.receiver = '李四'
  );

-- ---------- mall_order：收货人快照列 ----------
USE mall_order;

SET @ddl = IF (
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = 'mall_order' AND TABLE_NAME = 'order' AND COLUMN_NAME = 'receiver_name') = 0,
  'ALTER TABLE `order` ADD COLUMN receiver_name VARCHAR(50) NULL AFTER address_id',
  'SELECT ''[skip] order.receiver_name already exists'' AS note'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF (
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = 'mall_order' AND TABLE_NAME = 'order' AND COLUMN_NAME = 'receiver_phone') = 0,
  'ALTER TABLE `order` ADD COLUMN receiver_phone VARCHAR(20) NULL AFTER receiver_name',
  'SELECT ''[skip] order.receiver_phone already exists'' AS note'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF (
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = 'mall_order' AND TABLE_NAME = 'order' AND COLUMN_NAME = 'receiver_address') = 0,
  'ALTER TABLE `order` ADD COLUMN receiver_address VARCHAR(300) NULL AFTER receiver_phone',
  'SELECT ''[skip] order.receiver_address already exists'' AS note'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
