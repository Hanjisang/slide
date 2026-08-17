package com.medreport.system;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface SystemConfigMapper {
    @Select("SELECT id,config_key,config_value,description,created_at,updated_at FROM system_config ORDER BY config_key")
    List<Map<String, Object>> listAll();
}

