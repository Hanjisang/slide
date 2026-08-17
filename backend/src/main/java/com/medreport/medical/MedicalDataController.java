package com.medreport.medical;

import com.medreport.common.ApiResponse;
import com.medreport.governance.ValidationService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/medical-data")
public class MedicalDataController {
    private final MedicalDataService service;
    private final ValidationService validation;

    public MedicalDataController(MedicalDataService service, ValidationService validation) {
        this.service = service;
        this.validation = validation;
    }

    @GetMapping("/{type}")
    public ApiResponse<Map<String, Object>> list(@PathVariable String type,
                                                 @RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "20") int size,
                                                 @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(Map.of("items", service.list(type, page, size, keyword), "total", service.count(type, keyword), "page", page, "size", size));
    }

    @PostMapping("/{type}")
    public ApiResponse<Map<String, Object>> create(@PathVariable String type, @RequestBody Map<String, Object> body) {
        long id = service.create(type, body);
        validation.validate(type.toUpperCase(), id);
        return ApiResponse.ok(Map.of("id", id));
    }

    @PutMapping("/{type}/{id}")
    public ApiResponse<Void> update(@PathVariable String type, @PathVariable long id, @RequestBody Map<String, Object> body) {
        service.update(type, id, body);
        validation.validate(type.toUpperCase(), id);
        return ApiResponse.ok();
    }
}
