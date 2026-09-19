-- ============================================================
-- NovaMall 新星商城 · 数据库初始化脚本
-- MySQL 8.0+
-- 执行：mysql -uroot -p < sql/novamall.sql
-- ============================================================

CREATE DATABASE IF NOT EXISTS `novamall`
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_general_ci;

USE `novamall`;

-- ------------------------------------------------------------
-- 用户表
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `t_user`;
CREATE TABLE `t_user`
(
    `id`          BIGINT       NOT NULL COMMENT '雪花算法ID',
    `username`    VARCHAR(64)  NOT NULL COMMENT '用户名',
    `password`    VARCHAR(128) NOT NULL COMMENT 'BCrypt 哈希后的密码',
    `phone`       VARCHAR(20)  DEFAULT NULL COMMENT '手机号',
    `email`       VARCHAR(128) DEFAULT NULL COMMENT '邮箱',
    `role_code`   VARCHAR(32)  NOT NULL DEFAULT 'USER' COMMENT '角色：USER/ADMIN/OPERATOR',
    `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1正常 0禁用',
    `deleted`     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除 1已删除',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`, `deleted`),
    KEY `idx_create_time` (`create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='用户表';

-- ------------------------------------------------------------
-- 商品分类
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `t_category`;
CREATE TABLE `t_category`
(
    `id`         BIGINT      NOT NULL,
    `name`       VARCHAR(64) NOT NULL COMMENT '分类名',
    `parent_id`  BIGINT      NOT NULL DEFAULT 0 COMMENT '父分类ID，0为顶级',
    `sort`       INT         NOT NULL DEFAULT 0 COMMENT '排序',
    `status`     TINYINT     NOT NULL DEFAULT 1,
    `deleted`    TINYINT     NOT NULL DEFAULT 0,
    `create_time` DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_parent_id` (`parent_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='商品分类表';

-- ------------------------------------------------------------
-- 商品表
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `t_product`;
CREATE TABLE `t_product`
(
    `id`             BIGINT          NOT NULL,
    `name`           VARCHAR(128)    NOT NULL COMMENT '商品名',
    `subtitle`       VARCHAR(255)    DEFAULT NULL COMMENT '副标题',
    `category_id`    BIGINT          DEFAULT NULL,
    `main_image`     VARCHAR(512)    DEFAULT NULL COMMENT '主图URL',
    `price`          DECIMAL(10, 2)  NOT NULL COMMENT '售价',
    `original_price` DECIMAL(10, 2)  DEFAULT NULL COMMENT '原价（划线价）',
    `stock`          INT             NOT NULL DEFAULT 0 COMMENT '库存',
    `sales`          INT             NOT NULL DEFAULT 0 COMMENT '销量（冗余字段）',
    `status`         TINYINT         NOT NULL DEFAULT 1 COMMENT '1上架 0下架',
    `version`        INT             NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    `deleted`        TINYINT         NOT NULL DEFAULT 0,
    `create_time`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_category_status` (`category_id`, `status`),
    KEY `idx_sales` (`sales`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='商品表';

-- ------------------------------------------------------------
-- 订单主表
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `t_order`;
CREATE TABLE `t_order`
(
    `id`           BIGINT          NOT NULL,
    -- 订单号建唯一索引：这是防重复下单的最后一道防线
    `order_no`     VARCHAR(32)     NOT NULL COMMENT '业务订单号',
    `user_id`      BIGINT          NOT NULL,
    `total_amount` DECIMAL(10, 2)  NOT NULL COMMENT '订单总额',
    `pay_amount`   DECIMAL(10, 2)  NOT NULL COMMENT '实付金额',
    `status`       TINYINT         NOT NULL DEFAULT 0 COMMENT '0待付款 1已付款 2已发货 3已完成 4已关闭',
    `pay_time`     DATETIME        DEFAULT NULL,
    `close_time`   DATETIME        DEFAULT NULL,
    `create_time`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_no` (`order_no`),
    -- 超时关单任务的核心索引：避免全表扫描
    KEY `idx_status_create_time` (`status`, `create_time`),
    KEY `idx_user_id` (`user_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='订单主表';

-- ------------------------------------------------------------
-- 订单明细表（冗余商品快照）
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `t_order_item`;
CREATE TABLE `t_order_item`
(
    `id`            BIGINT          NOT NULL,
    `order_id`      BIGINT          NOT NULL,
    `product_id`    BIGINT          NOT NULL,
    `product_name`  VARCHAR(128)    NOT NULL COMMENT '商品名快照',
    `product_image` VARCHAR(512)    DEFAULT NULL COMMENT '主图快照',
    `price`         DECIMAL(10, 2)  NOT NULL COMMENT '下单时单价快照',
    `quantity`      INT             NOT NULL,
    `total_amount`  DECIMAL(10, 2)  NOT NULL COMMENT '小计',
    PRIMARY KEY (`id`),
    KEY `idx_order_id` (`order_id`),
    KEY `idx_product_id` (`product_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='订单明细表';

-- ------------------------------------------------------------
-- 本地消息表（分布式事务：可靠消息最终一致性）
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `t_local_message`;
CREATE TABLE `t_local_message`
(
    `id`              BIGINT      NOT NULL,
    `biz_key`         VARCHAR(64) NOT NULL COMMENT '业务唯一标识（订单号）',
    `topic`           VARCHAR(64) NOT NULL COMMENT '消息主题',
    `body`            TEXT COMMENT '消息体 JSON',
    `status`          TINYINT     NOT NULL DEFAULT 0 COMMENT '0待发送 1已发送 2发送失败',
    `retry_count`     INT         NOT NULL DEFAULT 0 COMMENT '已重试次数',
    `max_retry`       INT         NOT NULL DEFAULT 5,
    `next_retry_time` DATETIME    DEFAULT NULL COMMENT '下次重试时间（指数退避）',
    `create_time`     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_biz_key_topic` (`biz_key`, `topic`),
    -- 重投任务的扫描索引
    KEY `idx_status_next_retry` (`status`, `next_retry_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='本地消息表';

-- ------------------------------------------------------------
-- 秒杀活动表
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `t_seckill_activity`;
CREATE TABLE `t_seckill_activity`
(
    `id`          BIGINT          NOT NULL,
    `product_id`  BIGINT          NOT NULL,
    `seckill_price` DECIMAL(10,2) NOT NULL COMMENT '秒杀价',
    `stock`       INT             NOT NULL COMMENT '秒杀库存',
    `start_time`  DATETIME        NOT NULL,
    `end_time`    DATETIME        NOT NULL,
    `status`      TINYINT         NOT NULL DEFAULT 0 COMMENT '0未开始 1进行中 2已结束',
    `create_time` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_product_id` (`product_id`),
    KEY `idx_start_time` (`start_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='秒杀活动表';

-- ============================================================
-- 初始数据（方便 clone 下来就能跑）
-- ============================================================
INSERT INTO `t_category` (`id`, `name`, `parent_id`, `sort`) VALUES
 (1, '手机数码', 0, 1),
 (2, '家用电器', 0, 2),
 (3, '服饰鞋包', 0, 3);

INSERT INTO `t_product` (`id`, `name`, `subtitle`, `category_id`, `price`, `original_price`, `stock`, `sales`, `status`)
VALUES
 (10001, 'NovaPhone 15 Pro', '钛金属边框 / A17 芯片 / 4800万像素', 1, 5999.00, 6999.00, 1000, 128, 1),
 (10002, 'NovaBook Air 13', '轻薄本 / 16G+512G / 视网膜屏',       1, 4999.00, 5499.00, 500,  66,  1),
 (10003, 'NovaBuds 无线耳机', '主动降噪 / 40小时续航',             1, 399.00,  499.00,  2000, 512, 1),
 (10004, 'NovaWatch S8',     '血氧监测 / 独立通话',               2, 1299.00, 1499.00, 800,  88,  1),
 (10005, 'Nova 空气炸锅',     '5L 大容量 / 无油低脂',              2, 299.00,  399.00,  1500, 233, 1);

-- 初始管理员：用户名 admin，密码 admin12345（BCrypt 加密后的值）
-- 注意：这是演示数据，生产环境必须强制首次登录改密码
INSERT INTO `t_user` (`id`, `username`, `password`, `role_code`, `status`) VALUES
 (1, 'admin', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi', 'ADMIN', 1),
 (2, 'test',  '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi', 'USER', 1);
