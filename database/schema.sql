-- Ansik backend schema for Cloud SQL for MySQL 8.4.
-- Connect as root, select the ansik-db Cloud SQL instance, and run this file once.

SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;
SET time_zone = '+00:00';

CREATE DATABASE IF NOT EXISTS ansik_db
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;

USE ansik_db;

-- Cache only third-party enrichment such as AI/menu/search results.
-- Tourism OpenAPI responses are deliberately excluded and fetched in real time
-- so that the contest organizer can verify actual API usage.
CREATE TABLE IF NOT EXISTS external_api_cache (
    key_hash CHAR(64) NOT NULL COMMENT 'SHA-256 cache key',
    namespace VARCHAR(48) NOT NULL COMMENT 'Cached provider/data category',
    cache_key VARCHAR(768) NOT NULL COMMENT 'Original normalized cache key',
    payload LONGTEXT NOT NULL COMMENT 'JSON response or image URL metadata; no image binary',
    expires_at DATETIME(6) NOT NULL COMMENT 'Normal cache expiry time (UTC)',
    stale_until DATETIME(6) NOT NULL COMMENT 'Last time usable as fallback during provider failure (UTC)',
    updated_at DATETIME(6) NOT NULL COMMENT 'Last refresh time (UTC)',
    PRIMARY KEY (key_hash),
    KEY idx_external_cache_namespace (namespace),
    KEY idx_external_cache_stale_until (stale_until)
) ENGINE=InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;

-- Remove tourism API response caches created by older server builds. These are
-- derived API responses, not user data, and the current application never
-- recreates these namespaces.
DELETE FROM external_api_cache WHERE namespace LIKE 'tour-%';

-- Ansik member account. Social-provider IDs are kept separately so that one
-- member can link more than one login provider later.
CREATE TABLE IF NOT EXISTS users (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    nickname VARCHAR(80) NOT NULL,
    profile_image_url VARCHAR(2048) NULL,
    preferred_language VARCHAR(10) NOT NULL DEFAULT 'ko',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    last_login_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT chk_users_status CHECK (status IN ('ACTIVE', 'BLOCKED', 'DELETED')),
    KEY idx_users_status (status),
    KEY idx_users_last_login_at (last_login_at)
) ENGINE=InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS social_accounts (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    provider VARCHAR(16) NOT NULL COMMENT 'KAKAO, NAVER, or GOOGLE',
    provider_user_id VARCHAR(191) NOT NULL,
    email VARCHAR(320) NULL,
    display_name VARCHAR(80) NULL,
    profile_image_url VARCHAR(2048) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_login_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_social_provider_user (provider, provider_user_id),
    UNIQUE KEY uk_social_user_provider (user_id, provider),
    CONSTRAINT chk_social_provider CHECK (provider IN ('KAKAO', 'NAVER', 'GOOGLE')),
    CONSTRAINT fk_social_accounts_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;

-- Store language-independent condition codes such as PEANUT_ALLERGY,
-- LOW_SODIUM, VEGAN, or HALAL. Translated labels stay in the Android app.
CREATE TABLE IF NOT EXISTS user_health_conditions (
    user_id BIGINT UNSIGNED NOT NULL,
    condition_code VARCHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id, condition_code),
    KEY idx_health_condition_code (condition_code),
    CONSTRAINT fk_health_conditions_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS user_preferences (
    user_id BIGINT UNSIGNED NOT NULL,
    search_radius_m INT UNSIGNED NOT NULL DEFAULT 2000,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id),
    CONSTRAINT chk_search_radius_m CHECK (search_radius_m BETWEEN 100 AND 20000),
    CONSTRAINT fk_user_preferences_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS saved_courses (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    course_type VARCHAR(16) NOT NULL COMMENT 'AI or MANUAL',
    title VARCHAR(120) NOT NULL,
    destination_code VARCHAR(32) NULL,
    nights SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    days SMALLINT UNSIGNED NOT NULL DEFAULT 1,
    preferences_json JSON NULL,
    existing_schedule MEDIUMTEXT NULL,
    ai_summary TEXT NULL,
    selection_reason TEXT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT chk_saved_course_type CHECK (course_type IN ('AI', 'MANUAL')),
    CONSTRAINT chk_saved_course_duration CHECK (days >= 1 AND nights < days),
    KEY idx_saved_courses_user_updated (user_id, updated_at),
    CONSTRAINT fk_saved_courses_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS saved_course_places (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    course_id BIGINT UNSIGNED NOT NULL,
    day_number SMALLINT UNSIGNED NOT NULL,
    visit_order SMALLINT UNSIGNED NOT NULL,
    content_id VARCHAR(64) NULL COMMENT 'TourAPI content ID when available',
    place_name VARCHAR(255) NOT NULL,
    address VARCHAR(500) NULL,
    latitude DECIMAL(10, 7) NOT NULL,
    longitude DECIMAL(10, 7) NOT NULL,
    recommended_time VARCHAR(40) NULL,
    visit_reason TEXT NULL,
    visit_tip TEXT NULL,
    PRIMARY KEY (id),
    CONSTRAINT chk_course_place_day_order CHECK (day_number >= 1 AND visit_order >= 1),
    CONSTRAINT chk_course_place_latitude CHECK (latitude BETWEEN -90 AND 90),
    CONSTRAINT chk_course_place_longitude CHECK (longitude BETWEEN -180 AND 180),
    UNIQUE KEY uk_course_day_visit_order (course_id, day_number, visit_order),
    KEY idx_course_places_content_id (content_id),
    CONSTRAINT fk_saved_course_places_course
        FOREIGN KEY (course_id) REFERENCES saved_courses (id) ON DELETE CASCADE
) ENGINE=InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;

-- Only a SHA-256 digest is stored here; the raw refresh token is never stored.
CREATE TABLE IF NOT EXISTS refresh_tokens (
    token_hash CHAR(64) NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    device_id VARCHAR(191) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (token_hash),
    KEY idx_refresh_tokens_user_device (user_id, device_id),
    KEY idx_refresh_tokens_expires_at (expires_at),
    CONSTRAINT fk_refresh_tokens_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;

-- Cache inspection. This returns zero rows until the app successfully caches
-- a third-party enrichment response. No namespace beginning with "tour-"
-- should appear after the contest-compliant server is deployed.
SELECT
    namespace,
    COUNT(*) AS cached_items,
    ROUND(SUM(OCTET_LENGTH(payload)) / 1024 / 1024, 2) AS payload_mb,
    MAX(updated_at) AS latest_update
FROM external_api_cache
GROUP BY namespace
ORDER BY namespace;

-- Basic deployment verification. Every value should be utf8mb4/UTC-compatible.
SELECT DATABASE() AS active_database, @@character_set_database AS character_set,
       @@collation_database AS collation, @@session.time_zone AS session_time_zone;
