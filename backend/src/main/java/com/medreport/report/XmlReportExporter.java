package com.medreport.report;

import com.medreport.report.ReportModels.ExportResult;
import com.medreport.report.ReportModels.ReportContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamWriter;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class XmlReportExporter implements ReportExporter {
    private final Path reportDir;

    public XmlReportExporter(@Value("${app.report-dir}") String reportDir) {
        this.reportDir = Path.of(reportDir);
    }

    @Override
    public boolean supports(String format) {
        return "XML".equalsIgnoreCase(format);
    }

    @Override
    public ExportResult export(ReportContext context) {
        try {
            Files.createDirectories(reportDir);
            Path path = reportDir.resolve(context.batchNo() + ".xml");
            try (OutputStream output = Files.newOutputStream(path)) {
                XMLStreamWriter xml = XMLOutputFactory.newFactory().createXMLStreamWriter(output, "UTF-8");
                xml.writeStartDocument("UTF-8", "1.0");
                xml.writeStartElement("report");
                element(xml, "batchNo", context.batchNo());
                element(xml, "reportType", context.reportType());
                xml.writeStartElement("records");
                for (var record : context.records()) {
                    xml.writeStartElement("record");
                    for (var entry : record.entrySet()) {
                        xml.writeStartElement("field");
                        xml.writeAttribute("name", entry.getKey());
                        if (entry.getValue() != null) xml.writeCharacters(String.valueOf(entry.getValue()));
                        xml.writeEndElement();
                    }
                    xml.writeEndElement();
                }
                xml.writeEndElement();
                xml.writeEndElement();
                xml.writeEndDocument();
                xml.close();
            }
            return new ExportResult(path.toAbsolutePath(), "XML", Files.size(path));
        } catch (Exception ex) {
            throw new IllegalStateException("XML 导出失败", ex);
        }
    }

    private void element(XMLStreamWriter xml, String name, String value) throws Exception {
        xml.writeStartElement(name);
        xml.writeCharacters(value);
        xml.writeEndElement();
    }
}

