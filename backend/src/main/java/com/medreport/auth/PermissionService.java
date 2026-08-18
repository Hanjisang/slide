package com.medreport.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PermissionService {
    private final JdbcTemplate jdbc;
    public PermissionService(JdbcTemplate jdbc){this.jdbc=jdbc;}

    public boolean hasAny(String role,String[] permissions){
        if("ADMIN".equals(role))return true;
        if(permissions.length==0)return true;
        String placeholders=String.join(",",java.util.Collections.nCopies(permissions.length,"?"));
        Object[] args=new Object[permissions.length+1];args[0]=role;System.arraycopy(permissions,0,args,1,permissions.length);
        Integer count=jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_role r JOIN sys_role_permission rp ON rp.role_id=r.id
                JOIN sys_permission p ON p.id=rp.permission_id WHERE r.role_code=? AND r.enabled=1
                AND p.permission_code IN ("""+placeholders+")",Integer.class,args);
        return count!=null&&count>0;
    }

    public List<String> permissions(String role){
        if("ADMIN".equals(role))return jdbc.queryForList("SELECT permission_code FROM sys_permission ORDER BY permission_code",String.class);
        return jdbc.queryForList("""
                SELECT p.permission_code FROM sys_role r JOIN sys_role_permission rp ON rp.role_id=r.id
                JOIN sys_permission p ON p.id=rp.permission_id WHERE r.role_code=? AND r.enabled=1 ORDER BY p.permission_code
                """,String.class,role);
    }
}
