-- Upgrade a NetBackup v1 database to v2.
-- You do NOT need this file if you start v2 with spring.jpa.hibernate.ddl-auto=update (the default):
-- Hibernate performs the same changes automatically. Run it manually only if you prefer SQL migrations,
-- and run it ONCE (MySQL 8 has no "ADD COLUMN IF NOT EXISTS").
USE netbackup;

CREATE TABLE IF NOT EXISTS app_user (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(255) NOT NULL UNIQUE,
  password_hash VARCHAR(255),
  role VARCHAR(255),
  enabled BIT(1) NOT NULL,
  created_at DATETIME(6)
);

CREATE TABLE IF NOT EXISTS audit_log (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(255), action VARCHAR(255),
  device_id BIGINT, device_name VARCHAR(255), version INT,
  status VARCHAR(255), details VARCHAR(500), created_at DATETIME(6),
  INDEX idx_audit_time (created_at)
);

ALTER TABLE config_backup
  ADD COLUMN risk_level VARCHAR(255),
  ADD COLUMN risk_score INT,
  ADD COLUMN risk_details TEXT;

ALTER TABLE device ADD COLUMN auto_backup BIT(1) NULL;

-- v1 admin accounts become ADMIN users. Their SHA-256 hash is wrapped as {sha256}<hash> and is replaced by a
-- BCrypt hash automatically the first time that user logs in. (DataSeeder does this too when app_user is empty.)
INSERT IGNORE INTO app_user (username, password_hash, role, enabled, created_at)
SELECT username, CONCAT('{sha256}', password_hash), 'ADMIN', 1, NOW() FROM admin;

-- Existing config_backup rows get their risk rating when the application starts (DataSeeder.backfillRisk).
-- Optional clean-up once you are happy with v2:  DROP TABLE admin;
