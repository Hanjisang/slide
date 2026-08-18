package com.medreport.auth;

import com.medreport.common.BizException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AuthInterceptor implements HandlerInterceptor {
    public static final String SESSION_ATTRIBUTE = "authenticatedSession";
    private final TokenService tokenService;
    private final PermissionService permissions;

    public AuthInterceptor(TokenService tokenService, PermissionService permissions) {
        this.tokenService = tokenService;
        this.permissions = permissions;
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
        if(handler instanceof HandlerMethod method){
            RequirePermission required=method.getMethodAnnotation(RequirePermission.class);
            if(required==null)required=method.getBeanType().getAnnotation(RequirePermission.class);
            if(required!=null&&!permissions.hasAny(session.role(),required.value()))throw new BizException(403,"无权执行此操作");
        }
        return true;
    }
}
