-- Run as root after database/schema.sql.
-- The Cloud SQL console initially grants cloudsqlsuperuser to built-in users.
-- Ansik only needs data access at runtime because schema changes are run manually.

REVOKE 'cloudsqlsuperuser' FROM 'ansik_app'@'%';
SET DEFAULT ROLE NONE TO 'ansik_app'@'%';

GRANT SELECT, INSERT, UPDATE, DELETE
ON ansik_db.*
TO 'ansik_app'@'%';

SHOW GRANTS FOR 'ansik_app'@'%';
