-- v0.2.0 introduces storage targets, archive/file/backup tasks, RBAC, report plans and alerts.
-- New installations receive the complete schema from backend/src/main/resources/schema.sql.
-- Existing installations are upgraded idempotently by DatabaseUpgradeService because MySQL
-- column-existence syntax differs between supported 8.x patch releases.
SELECT 'v0.2.0 schema upgrades are managed by DatabaseUpgradeService' AS migration_note;
