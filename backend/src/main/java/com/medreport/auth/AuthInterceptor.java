package com.medreport.auth;

import com.medreport.common.BizException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AuthInterceptor implements HandlerInterceptor {
    public static final String SESSION_ATTRIBUTE = "authenticatedSession";
    private final TokenService tokenService;

    public AuthInterceptor(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String header = request.getHeader("Authorization");
        String token = header != null && header.startsWith("Bearer ") ? header.substring(7) : request.getParameter("access_token");
        TokenService.Session session = token == null ? null : tokenService.verify(token);
        if (session == null) {
            throw new BizException(401, "登录已失效，请重新登录");
        }
        request.setAttribute(SESSION_ATTRIBUTE, session);
        return true;
    }
}
