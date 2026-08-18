package com.medreport.auth;

import com.medreport.common.BizException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuthInterceptorTest {
    static class SecuredController {@RequirePermission("SLIDE_DELETE") public void delete() {}}

    @Test
    void rejectsAuthenticatedUserWithoutRequiredPermission() throws Exception {
        TokenService tokens=new TokenService();String token=tokens.issue(2,"viewer","VIEWER");
        PermissionService permissions=mock(PermissionService.class);when(permissions.hasAny(eq("VIEWER"),any(String[].class))).thenReturn(false);
        MockHttpServletRequest request=new MockHttpServletRequest();request.addHeader("Authorization","Bearer "+token);
        HandlerMethod handler=new HandlerMethod(new SecuredController(),SecuredController.class.getMethod("delete"));

        BizException error=assertThrows(BizException.class,()->new AuthInterceptor(tokens,permissions).preHandle(request,new MockHttpServletResponse(),handler));

        assertEquals(403,error.getCode());
    }
}
