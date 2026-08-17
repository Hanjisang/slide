CREATE DATABASE IF NOT EXISTS medical_report CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS mock_hospital CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
GRANT ALL PRIVILEGES ON medical_report.* TO 'medical'@'%';
GRANT ALL PRIVILEGES ON mock_hospital.* TO 'medical'@'%';
FLUSH PRIVILEGES;

