package com.medreport.slide.storage;

import com.medreport.common.BizException;
import com.medreport.security.SecretCipher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class StorageTargetService {
    private final JdbcTemplate jdbc;
    private final SecretCipher cipher;

    public StorageTargetService(JdbcTemplate jdbc, SecretCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    public StorageTarget find(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM storage_target WHERE id=?", id);
        if (rows.isEmpty()) throw new BizException("存储目标不存在");
        return map(rows.getFirst());
    }

    public StorageTarget requireClass(String storageClass) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM storage_target WHERE storage_class=? AND enabled=1 ORDER BY id LIMIT 1",
                storageClass.toUpperCase(Locale.ROOT));
        if (rows.isEmpty()) throw new BizException("未配置可用的 " + storageClass + " 存储目标");
        return map(rows.getFirst());
    }

    public List<Map<String, Object>> listSafe() {
        return jdbc.queryForList("""
                SELECT id,name,storage_type,endpoint,bucket,base_path,storage_class,enabled,created_at,updated_at
                FROM storage_target ORDER BY id
                """);
    }

    public long save(Long id, Map<String, Object> body) {
        String name = text(body, "name");
        String type = text(body, "storageType").toUpperCase(Locale.ROOT);
        String storageClass = text(body, "storageClass").toUpperCase(Locale.ROOT);
        if (!List.of("S3", "FILE").contains(type)) throw new BizException("storageType 仅支持 S3/FILE");
        if (!List.of("HOT", "ARCHIVE", "BACKUP", "TEST").contains(storageClass)) throw new BizException("存储等级无效");
        if (!"S3".equals(type)) throw new BizException("v0.2.0 当前仅开放 S3 存储目标");
        String endpoint = text(body, "endpoint");
        String bucket = text(body, "bucket");
        String accessKey = body.get("accessKey") == null ? null : String.valueOf(body.get("accessKey"));
        String secretKey = body.get("secretKey") == null ? null : String.valueOf(body.get("secretKey"));
        boolean enabled = bool(body.getOrDefault("enabled", true));
        if (id == null) {
            if (accessKey == null || secretKey == null) throw new BizException("新增 S3 目标必须提供 Access Key 和 Secret Key");
            jdbc.update("""
                    INSERT INTO storage_target(name,storage_type,endpoint,access_key_encrypted,secret_key_encrypted,bucket,base_path,storage_class,enabled)
                    VALUES (?,?,?,?,?,?,?,?,?)
                    """, name, type, endpoint, cipher.encrypt(accessKey), cipher.encrypt(secretKey), bucket,
                    body.getOrDefault("basePath", ""), storageClass, enabled);
            return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        }
        Map<String, Object> current = jdbc.queryForMap("SELECT access_key_encrypted,secret_key_encrypted FROM storage_target WHERE id=?", id);
        jdbc.update("""
                UPDATE storage_target SET name=?,storage_type=?,endpoint=?,access_key_encrypted=?,secret_key_encrypted=?,bucket=?,base_path=?,storage_class=?,enabled=? WHERE id=?
                """, name, type, endpoint,
                accessKey == null || accessKey.isBlank() ? current.get("access_key_encrypted") : cipher.encrypt(accessKey),
                secretKey == null || secretKey.isBlank() ? current.get("secret_key_encrypted") : cipher.encrypt(secretKey),
                bucket, body.getOrDefault("basePath", ""), storageClass, enabled, id);
        return id;
    }

    private StorageTarget map(Map<String, Object> row) {
        return new StorageTarget(((Number) row.get("id")).longValue(), String.valueOf(row.get("name")),
                String.valueOf(row.get("storage_type")), String.valueOf(row.get("endpoint")),
                cipher.decrypt((String) row.get("access_key_encrypted")), cipher.decrypt((String) row.get("secret_key_encrypted")),
                String.valueOf(row.get("bucket")), row.get("base_path") == null ? "" : String.valueOf(row.get("base_path")),
                String.valueOf(row.get("storage_class")), bool(row.get("enabled")));
    }

    private String text(Map<String, Object> body, String key) {
        String value = body.get(key) == null ? "" : String.valueOf(body.get(key)).trim();
        if (value.isEmpty()) throw new BizException(key + " 不能为空");
        return value;
    }

    private boolean bool(Object value) {
        return value instanceof Boolean b ? b : value instanceof Number n ? n.intValue() != 0 : Boolean.parseBoolean(String.valueOf(value));
    }
}
