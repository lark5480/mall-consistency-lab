SET NAMES utf8mb4;
CREATE DATABASE IF NOT EXISTS mall_product DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE mall_product;

CREATE TABLE IF NOT EXISTS category (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  name VARCHAR(50) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_category_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS product (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  name VARCHAR(100) NOT NULL,
  description VARCHAR(500) NULL,
  price DECIMAL(10,2) NOT NULL,
  stock INT NOT NULL,
  category_id BIGINT UNSIGNED NOT NULL,
  image_url VARCHAR(255) NULL,
  version INT NOT NULL DEFAULT 0,
  status TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  KEY idx_product_category (category_id),
  KEY idx_product_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS stock_dedup_log (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  order_no VARCHAR(64) NOT NULL,
  product_id BIGINT UNSIGNED NOT NULL,
  count INT NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'DEDUCTED',
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_dedup_order_no (order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO category (id, name) VALUES
(1, '数码'), (2, '服饰');

INSERT INTO product (id, name, description, price, stock, category_id, image_url, version, status) VALUES
(1, '学习手机', '适合开发调试的演示手机', 2999.00, 100, 1, 'https://picsum.photos/seed/phone/800/500', 0, 1),
(2, '限量卫衣', '仅剩少量库存的测试商品', 199.00, 5, 2, 'https://picsum.photos/seed/hoodie/800/500', 0, 1);
