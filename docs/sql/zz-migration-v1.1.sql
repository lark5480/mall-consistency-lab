-- ============================================================
-- Mall-Learning v1.0 -> v1.1 幂等迁移脚本（可重复执行）
-- 变更内容：
--   1. mall_user.user        新增 role 列（USER/ADMIN，默认 USER）
--   2. mall_product.stock_dedup_log 新增 status 列（DEDUCTED/RESTORED）
--   3. 补种默认管理员 admin/admin123（已存在则跳过）
-- 使用方式：
--   全新环境：docker compose 初始化 SQL 按文件名字母序执行，
--             本文件以 zz- 前缀保证最后运行，无需手动处理。
--   旧数据卷：docker exec -i <mysql容器> mysql -uroot -proot < docs/sql/zz-migration-v1.1.sql
-- 说明：admin 密码仅本地演示用，生产环境必须修改。
-- ============================================================
SET NAMES utf8mb4;

-- ---------- mall_user：user.role ----------
USE mall_user;
SET @ddl = IF (
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = 'mall_user' AND TABLE_NAME = 'user' AND COLUMN_NAME = 'role') = 0,
  'ALTER TABLE `user` ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT ''USER'' AFTER status',
  'SELECT ''[skip] mall_user.user.role already exists'' AS note'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

INSERT INTO `user` (username, password_hash, phone, email, avatar, status, role)
SELECT 'admin', '$2b$12$2UgSnIdzD50LwGAWSYkLwe3TARmbm1DwcxGdrt1aP46fEbmwxeqKW', NULL, NULL, NULL, 1, 'ADMIN'
WHERE NOT EXISTS (SELECT 1 FROM `user` WHERE username = 'admin');

-- 兼容脏数据：旧卷中可能存在早期注册产生的同名 admin（role=USER），强制提权
UPDATE `user` SET role = 'ADMIN', password_hash =
  '$2b$12$2UgSnIdzD50LwGAWSYkLwe3TARmbm1DwcxGdrt1aP46fEbmwxeqKW'
WHERE username = 'admin' AND role <> 'ADMIN';

-- 兼容脏数据：旧卷中 demo 若仍是不可登录的占位哈希，重置为真实 BCrypt('123456')；
-- 若用户已自行修改过密码（哈希不等于占位值）则不动
UPDATE `user` SET password_hash =
  '$2a$10$O1Wc6FycFSYwy8t9m04hoeM1WN.JIsi1KAvHH0JSAtIGRJwtM7ABy'
WHERE username = 'demo'
  AND password_hash = '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy';

-- ---------- mall_product：stock_dedup_log.status ----------
USE mall_product;
SET @ddl = IF (
  (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = 'mall_product' AND TABLE_NAME = 'stock_dedup_log' AND COLUMN_NAME = 'status') = 0,
  'ALTER TABLE stock_dedup_log ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT ''DEDUCTED'' AFTER count',
  'SELECT ''[skip] mall_product.stock_dedup_log.status already exists'' AS note'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
