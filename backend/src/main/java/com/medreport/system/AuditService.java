package com.medreport.system;

import com.medreport.auth.AuthInterceptor;
import com.medreport.auth.TokenService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AuditService {
    private final JdbcTemplate jdbc;

    public AuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void log(HttpServletRequest request, String operation, String module, Object businessId, String result, String detail) {
        log(username(request), operation, module, businessId, result, detail);
    }

    public static String username(HttpServletRequest request) {
        Object attribute = request.getAttribute(AuthInterceptor.SESSION_ATTRIBUTE);
        return attribute instanceof TokenService.Session session ? session.username() : "SYSTEM";
    }

    public void log(String username, String operation, String module, Object businessId, String result, String detail) {
        jdbc.update("INSERT INTO operation_log(username,operation,module,business_id,result,detail) VALUES (?,?,?,?,?,?)",
                username, operation, module, businessId == null ? null : businessId.toString(), result, detail);
    }
}
