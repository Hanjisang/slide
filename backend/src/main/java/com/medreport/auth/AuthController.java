package com.medreport.auth;

import com.medreport.common.ApiResponse;
import com.medreport.common.BizException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    public record LoginRequest(@NotBlank(message = "请输入用户名") String username,
                               @NotBlank(message = "请输入密码") String password) {}

    private final JdbcTemplate jdbc;
    private final TokenService tokenService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AuthController(JdbcTemplate jdbc, TokenService tokenService) {
        this.jdbc = jdbc;
        this.tokenService = tokenService;
    }

    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@Valid @RequestBody LoginRequest request) {
        var users = jdbc.queryForList("SELECT id,username,password_hash,display_name,role,enabled FROM sys_user WHERE username=?", request.username());
        if (users.isEmpty() || !Boolean.TRUE.equals(asBoolean(users.getFirst().get("enabled")))
                || !passwordEncoder.matches(request.password(), String.valueOf(users.getFirst().get("password_hash")))) {
            throw new BizException(401, "用户名或密码错误");
        }
        Map<String, Object> user = users.getFirst();
        long id = ((Number) user.get("id")).longValue();
        String token = tokenService.issue(id, request.username(), String.valueOf(user.get("role")));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", token);
        data.put("user", Map.of("id", id, "username", request.username(), "displayName", user.get("display_name"), "role", user.get("role")));
        return ApiResponse.ok(data);
    }

    @GetMapping("/me")
    public ApiResponse<TokenService.Session> me(HttpServletRequest request) {
        return ApiResponse.ok((TokenService.Session) request.getAttribute(AuthInterceptor.SESSION_ATTRIBUTE));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(@RequestHeader("Authorization") String authorization) {
        tokenService.revoke(authorization.substring(7));
        return ApiResponse.ok();
    }

    private boolean asBoolean(Object value) {
        return value instanceof Boolean b ? b : value instanceof Number n && n.intValue() != 0;
    }
}
