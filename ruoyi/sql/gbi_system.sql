-- gbi_system: RuoYi-style users / roles / departments plus GBI data permission.
-- Table shapes follow RuoYi-Cloud's ry-cloud.sql (trimmed to the columns used); gbi_* tables are new.
-- Demo password for every user: Gbi@2026 (BCrypt).
CREATE DATABASE IF NOT EXISTS gbi_system DEFAULT CHARACTER SET utf8mb4;
USE gbi_system;

DROP TABLE IF EXISTS sys_dept, sys_user, sys_role, sys_user_role, sys_role_dept, sys_role_perm, sys_logininfor,
  gbi_role_table, gbi_dept_data;

CREATE TABLE sys_dept (
  dept_id    BIGINT PRIMARY KEY,
  parent_id  BIGINT DEFAULT 0,
  ancestors  VARCHAR(50) DEFAULT '' COMMENT '祖级列表，如 0,100,101',
  dept_name  VARCHAR(30) NOT NULL,
  order_num  INT DEFAULT 0,
  status     CHAR(1) DEFAULT '0' COMMENT '0 正常 1 停用',
  del_flag   CHAR(1) DEFAULT '0'
) COMMENT '部门（组织）';

CREATE TABLE sys_user (
  user_id    BIGINT PRIMARY KEY,
  dept_id    BIGINT,
  user_name  VARCHAR(30) NOT NULL UNIQUE,
  nick_name  VARCHAR(30),
  password   VARCHAR(100) NOT NULL COMMENT 'BCrypt',
  status     CHAR(1) DEFAULT '0' COMMENT '0 正常 1 停用',
  del_flag   CHAR(1) DEFAULT '0'
) COMMENT '用户';

CREATE TABLE sys_role (
  role_id    BIGINT PRIMARY KEY,
  role_name  VARCHAR(30) NOT NULL,
  role_key   VARCHAR(100) NOT NULL,
  data_scope CHAR(1) DEFAULT '1' COMMENT '1 全部 2 自定义 3 本部门 4 本部门及以下 5 仅本人',
  status     CHAR(1) DEFAULT '0',
  del_flag   CHAR(1) DEFAULT '0'
) COMMENT '角色';

CREATE TABLE sys_user_role (user_id BIGINT, role_id BIGINT, PRIMARY KEY (user_id, role_id));
CREATE TABLE sys_role_dept (role_id BIGINT, dept_id BIGINT, PRIMARY KEY (role_id, dept_id)) COMMENT '自定义数据权限的部门';
-- RuoYi keeps permission strings on sys_menu (perms) joined through sys_role_menu; there is no menu UI here,
-- so the strings hang off the role directly.
CREATE TABLE sys_role_perm (role_id BIGINT, perms VARCHAR(100), PRIMARY KEY (role_id, perms)) COMMENT '功能权限';

CREATE TABLE sys_logininfor (
  info_id     BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_name   VARCHAR(50),
  ipaddr      VARCHAR(128),
  status      CHAR(1) COMMENT '0 成功 1 失败',
  msg         VARCHAR(255),
  access_time DATETIME
) COMMENT '登录日志';

-- GBI: which business tables a role may query (exact name or prefix*, '*' = all)
CREATE TABLE gbi_role_table (role_id BIGINT, table_pattern VARCHAR(64), PRIMARY KEY (role_id, table_pattern))
  COMMENT '表级数据权限';
-- GBI: what each department owns in the business data (dimension -> value), e.g. 华东店群 -> shop 1,2,3,7,10
CREATE TABLE gbi_dept_data (dept_id BIGINT, dim VARCHAR(32), dim_value VARCHAR(64), PRIMARY KEY (dept_id, dim, dim_value))
  COMMENT '部门绑定的业务数据（行级数据权限的取值来源）';

-- org: 总部 / 大区 / 店群 + 仓
INSERT INTO sys_dept (dept_id, parent_id, ancestors, dept_name, order_num) VALUES
 (100, 0,   '0',         '总部',       0),
 (101, 100, '0,100',     '华东大区',   1),
 (102, 100, '0,100',     '华北大区',   2),
 (103, 101, '0,100,101', '华东店群',   1),
 (104, 101, '0,100,101', '华东中心仓', 2),
 (105, 101, '0,100,101', '华东区域仓', 3),
 (106, 101, '0,100,101', '上海前置仓', 4),
 (107, 102, '0,100,102', '华北店群',   1),
 (108, 102, '0,100,102', '华北仓群',   2),
 (109, 100, '0,100',     '青岚品牌组', 3);

-- bindings come from the dataset: dim_shop / wms_warehouse joined to dim_region.big_region
INSERT INTO gbi_dept_data (dept_id, dim, dim_value) VALUES
 (103, 'shop', '1'), (103, 'shop', '2'), (103, 'shop', '3'), (103, 'shop', '7'), (103, 'shop', '10'),
 (104, 'warehouse', '1'), (105, 'warehouse', '3'), (106, 'warehouse', '7'),
 (107, 'shop', '4'), (107, 'shop', '11'),
 (108, 'warehouse', '4'), (108, 'warehouse', '8'),
 -- brand team (dataset v2: dim_shop.brand_id = 3 青岚 -> shops 4 天猫, 5 京东); the brand is the isolation unit
 (109, 'shop', '4'), (109, 'shop', '5');

INSERT INTO sys_role (role_id, role_name, role_key, data_scope) VALUES
 (1, '超级管理员',   'admin',     '1'),
 (2, '大区运营',     'region_ops','4'),
 (3, '仓库主管',     'wh_mgr',    '3'),
 (4, '财务分析',     'finance',   '1'),
 (5, '店铺运营',     'shop_ops',  '2'),
 (6, '外部审计',     'auditor',   '5'),
 (7, '品牌运营',     'brand_ops', '3');

INSERT INTO sys_role_perm (role_id, perms) VALUES
 (1, '*:*:*'),
 (2, 'gbi:query'), (2, 'gbi:report'),
 (3, 'gbi:query'),
 (4, 'gbi:query'), (4, 'gbi:report'),
 (5, 'gbi:query'),
 (6, 'gbi:query'),
 (7, 'gbi:query'), (7, 'gbi:report');

INSERT INTO gbi_role_table (role_id, table_pattern) VALUES
 (1, '*'),
 (2, '*'),
 (3, 'wms_*'), (3, 'tms_*'), (3, 'oms_*'), (3, 'scm_*'), (3, 'prd_*'), (3, 'dim_*'),
 (4, 'fin_*'), (4, 'trd_*'), (4, 'dim_*'),
 (5, 'trd_*'), (5, 'prd_*'), (5, 'mkt_*'), (5, 'usr_*'), (5, 'dim_*'),
 (6, '*'),
 (7, 'trd_*'), (7, 'prd_*'), (7, 'mkt_*'), (7, 'usr_*'), (7, 'dim_*');

-- shop_ops (custom scope): both regions' shop groups
INSERT INTO sys_role_dept (role_id, dept_id) VALUES (5, 103), (5, 107);

INSERT INTO sys_user (user_id, dept_id, user_name, nick_name, password) VALUES
 (1, 100, 'admin',    '管理员',       '$2a$10$OpBRPSZpGYjF3szYn6bZKOH3dI0qsp.tRcxVmZPTakNY1.DNaSM.O'),
 (2, 101, 'east_ops', '华东大区运营', '$2a$10$OpBRPSZpGYjF3szYn6bZKOH3dI0qsp.tRcxVmZPTakNY1.DNaSM.O'),
 (3, 106, 'sh_wh',    '上海前置仓主管','$2a$10$OpBRPSZpGYjF3szYn6bZKOH3dI0qsp.tRcxVmZPTakNY1.DNaSM.O'),
 (4, 100, 'finance',  '财务分析',     '$2a$10$OpBRPSZpGYjF3szYn6bZKOH3dI0qsp.tRcxVmZPTakNY1.DNaSM.O'),
 (5, 103, 'shop_ops', '店铺运营',     '$2a$10$OpBRPSZpGYjF3szYn6bZKOH3dI0qsp.tRcxVmZPTakNY1.DNaSM.O'),
 (6, 100, 'auditor',  '外部审计',     '$2a$10$OpBRPSZpGYjF3szYn6bZKOH3dI0qsp.tRcxVmZPTakNY1.DNaSM.O'),
 (7, 109, 'qinglan_ops','青岚品牌运营','$2a$10$OpBRPSZpGYjF3szYn6bZKOH3dI0qsp.tRcxVmZPTakNY1.DNaSM.O');

INSERT INTO sys_user_role (user_id, role_id) VALUES (1, 1), (2, 2), (3, 3), (4, 4), (5, 5), (6, 6), (7, 7);
