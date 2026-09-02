SET NAMES utf8mb4;
CREATE DATABASE IF NOT EXISTS mall_user DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE mall_user;

CREATE TABLE IF NOT EXISTS `user` (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  username VARCHAR(50) NOT NULL,
  password_hash VARCHAR(100) NOT NULL,
  phone VARCHAR(20) NULL,
  email VARCHAR(100) NULL,
  avatar VARCHAR(255) NULL,
  status TINYINT NOT NULL DEFAULT 1,
  role VARCHAR(20) NOT NULL DEFAULT 'USER',
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_username (username),
  KEY idx_user_phone (phone)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

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

INSERT INTO `user` (id, username, password_hash, phone, email, avatar, status, role) VALUES
(1, 'demo', '$2a$10$O1Wc6FycFSYwy8t9m04hoeM1WN.JIsi1KAvHH0JSAtIGRJwtM7ABy', '13800000000', 'demo@mall.local', NULL, 1, 'USER'),
(2, 'admin', '$2b$12$2UgSnIdzD50LwGAWSYkLwe3TARmbm1DwcxGdrt1aP46fEbmwxeqKW', NULL, NULL, NULL, 1, 'ADMIN');

INSERT INTO address (user_id, receiver, phone, province, city, district, detail, is_default)
SELECT u.id, '张三', '13800000001', '上海市', '上海市', '浦东新区', '学习路 1 号', 1
FROM `user` u WHERE u.username = 'demo'
AND NOT EXISTS (SELECT 1 FROM address a WHERE a.user_id = u.id);

INSERT INTO address (user_id, receiver, phone, province, city, district, detail, is_default)
SELECT u.id, '李四', '13800000002', '北京市', '北京市', '海淀区', '实践路 2 号', 0
FROM `user` u WHERE u.username = 'demo'
AND NOT EXISTS (SELECT 1 FROM address a WHERE a.user_id = u.id AND a.receiver = '李四');
