package com.medreport.governance;

import com.medreport.auth.RequirePermission;
import com.medreport.common.ApiResponse;
import com.medreport.common.BizException;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
@RequestMapping("/api/basic-data")
@RequirePermission("DICT_MANAGE")
public class BasicDataController {
    private static final List<String> HEADERS=List.of("sourceValue","targetValue","description","enabled");
    private final JdbcTemplate jdbc;
    public BasicDataController(JdbcTemplate jdbc){this.jdbc=jdbc;}

    @GetMapping("/export/{dictType}")
    public ResponseEntity<byte[]> export(@PathVariable String dictType,@RequestParam(defaultValue="CSV")String format)throws Exception{
        long dictionaryId=dictionaryId(dictType);List<Map<String,Object>> rows=jdbc.queryForList("SELECT source_value,target_value,description,enabled FROM dictionary_item WHERE dictionary_id=? ORDER BY id",dictionaryId);
        byte[] content="XLSX".equalsIgnoreCase(format)?xlsx(rows):csv(rows);
        String extension="XLSX".equalsIgnoreCase(format)?"xlsx":"csv";
        MediaType type="XLSX".equalsIgnoreCase(format)?MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"):MediaType.parseMediaType("text/csv");
        return ResponseEntity.ok().contentType(type).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename="+dictType+"."+extension).body(content);
    }

    @PostMapping(value="/import/{dictType}",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Map<String,Object>> importData(@PathVariable String dictType,@RequestParam(defaultValue="CSV")String format,@RequestPart MultipartFile file)throws Exception{
        long dictionaryId=dictionaryId(dictType);List<RowData> rows="XLSX".equalsIgnoreCase(format)?readXlsx(file):readCsv(file);
        List<Map<String,Object>> errors=new ArrayList<>();int success=0;
        for(RowData row:rows){
            try{
                if(row.sourceValue()==null||row.sourceValue().isBlank())throw new BizException("源值不能为空");
                if(row.targetValue()==null||row.targetValue().isBlank())throw new BizException("目标值不能为空");
                jdbc.update("""
                        INSERT INTO dictionary_item(dictionary_id,source_value,target_value,description,enabled) VALUES (?,?,?,?,?)
                        ON DUPLICATE KEY UPDATE target_value=VALUES(target_value),description=VALUES(description),enabled=VALUES(enabled)
                        """,dictionaryId,row.sourceValue(),row.targetValue(),row.description(),row.enabled());success++;
            }catch(Exception ex){errors.add(Map.of("row",row.rowNumber(),"reason",ex.getMessage()));}
        }
        return ApiResponse.ok(Map.of("successCount",success,"errorCount",errors.size(),"errors",errors));
    }

    private byte[] csv(List<Map<String,Object>> rows)throws IOException{
        StringWriter out=new StringWriter();try(CSVPrinter printer=new CSVPrinter(out,CSVFormat.DEFAULT.builder().setHeader(HEADERS.toArray(String[]::new)).get())){
            for(Map<String,Object> row:rows)printer.printRecord(row.get("source_value"),row.get("target_value"),row.get("description"),row.get("enabled"));}
        return ("\uFEFF"+out).getBytes(StandardCharsets.UTF_8);
    }

    private byte[] xlsx(List<Map<String,Object>> rows)throws IOException{
        try(Workbook workbook=new XSSFWorkbook();ByteArrayOutputStream out=new ByteArrayOutputStream()){
            Sheet sheet=workbook.createSheet("data");Row header=sheet.createRow(0);for(int i=0;i<HEADERS.size();i++)header.createCell(i).setCellValue(HEADERS.get(i));
            int index=1;for(Map<String,Object> value:rows){Row row=sheet.createRow(index++);row.createCell(0).setCellValue(String.valueOf(value.get("source_value")));
                row.createCell(1).setCellValue(String.valueOf(value.get("target_value")));row.createCell(2).setCellValue(value.get("description")==null?"":String.valueOf(value.get("description")));
                row.createCell(3).setCellValue(bool(value.get("enabled")));}workbook.write(out);return out.toByteArray();}
    }

    private List<RowData> readCsv(MultipartFile file)throws IOException{
        List<RowData> rows=new ArrayList<>();try(BufferedReader reader=new BufferedReader(new InputStreamReader(file.getInputStream(),StandardCharsets.UTF_8))){
            reader.mark(1);if(reader.read()!=0xFEFF)reader.reset();
            var records=CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get().parse(reader);int row=2;
            for(var record:records)rows.add(new RowData(row++,record.get("sourceValue"),record.get("targetValue"),record.isMapped("description")?record.get("description"):null,
                    !record.isMapped("enabled")||Boolean.parseBoolean(record.get("enabled"))));}return rows;
    }

    private List<RowData> readXlsx(MultipartFile file)throws IOException{
        List<RowData> rows=new ArrayList<>();try(Workbook workbook=WorkbookFactory.create(file.getInputStream())){Sheet sheet=workbook.getSheetAt(0);
            for(int i=1;i<=sheet.getLastRowNum();i++){Row row=sheet.getRow(i);if(row==null)continue;rows.add(new RowData(i+1,text(row.getCell(0)),text(row.getCell(1)),text(row.getCell(2)),boolCell(row.getCell(3))));}}
        return rows;
    }

    private long dictionaryId(String dictType){List<Long> ids=jdbc.queryForList("SELECT id FROM dictionary WHERE dict_type=?",Long.class,dictType.toUpperCase(Locale.ROOT));
        if(ids.isEmpty())throw new BizException("字典类型不存在");return ids.getFirst();}
    private String text(Cell cell){if(cell==null)return null;return new DataFormatter().formatCellValue(cell).trim();}
    private boolean boolCell(Cell cell){String value=text(cell);return value==null||value.isBlank()||Boolean.parseBoolean(value)||"1".equals(value);}
    private boolean bool(Object value){return value instanceof Boolean b?b:value instanceof Number n?n.intValue()!=0:Boolean.parseBoolean(String.valueOf(value));}
    private record RowData(int rowNumber,String sourceValue,String targetValue,String description,boolean enabled){}
}
