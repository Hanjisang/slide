package com.medreport.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medreport.report.ReportModels.ReportContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class ReportExporterTest {
    @TempDir Path tempDir;
    private final ReportContext context = new ReportContext("TEST001", "PATIENT", List.of(Map.of("id", 1, "name", "张三", "gender", "M")));

    @Test
    void exportsAllSupportedFormats() throws Exception {
        var json = new JsonReportExporter(new ObjectMapper(), tempDir.toString()).export(context);
        var xml = new XmlReportExporter(tempDir.toString()).export(context);
        var csv = new CsvReportExporter(tempDir.toString()).export(context);
        var zip = new ZipReportExporter(new ObjectMapper(), tempDir.toString()).export(context);

        assertTrue(Files.size(json.path()) > 0);
        assertTrue(Files.size(csv.path()) > 0);
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.path().toFile());
        try (ZipFile archive = new ZipFile(zip.path().toFile())) {
            assertNotNull(archive.getEntry("TEST001.json"));
        }
    }
}
