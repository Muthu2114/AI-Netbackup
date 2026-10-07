-- NetBackup v2 schema (optional: Hibernate creates/updates these automatically with ddl-auto=update).
CREATE DATABASE IF NOT EXISTS netbackup;
USE netbackup;

CREATE TABLE IF NOT EXISTS app_user (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(255) NOT NULL UNIQUE,
  password_hash VARCHAR(255),            -- BCrypt
  role VARCHAR(255),                     -- ADMIN | OPERATOR | VIEWER
  enabled BIT(1) NOT NULL,
  created_at DATETIME(6)
);

CREATE TABLE IF NOT EXISTS device (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(255),
  ip_address VARCHAR(255),
  type VARCHAR(255),
  vendor VARCHAR(255),
  location VARCHAR(255),
  status VARCHAR(255),
  current_version INT NOT NULL,
  last_backup_at DATETIME(6),
  auto_backup BIT(1)                     -- NULL is treated as true
);

CREATE TABLE IF NOT EXISTS config_backup (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  device_id BIGINT,
  version INT NOT NULL,
  file_name VARCHAR(255),
  content LONGTEXT,
  checksum VARCHAR(255),
  status VARCHAR(255),                   -- SUCCESS | FAILED
  source VARCHAR(255),                   -- SIMULATED | UPLOAD | RESTORE | SCHEDULED
  note VARCHAR(255),
  created_by VARCHAR(255),
  risk_level VARCHAR(255),               -- LOW | MEDIUM | HIGH
  risk_score INT,
  risk_details TEXT,
  created_at DATETIME(6),
  INDEX idx_backup_device (device_id)
);

CREATE TABLE IF NOT EXISTS audit_log (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(255),
  action VARCHAR(255),
  device_id BIGINT,
  device_name VARCHAR(255),
  version INT,
  status VARCHAR(255),                   -- SUCCESS | FAILED | DENIED
  details VARCHAR(500),
  created_at DATETIME(6),
  INDEX idx_audit_time (created_at)
);
-- Users, devices, versions and audit entries are inserted by DataSeeder.java on first start.
