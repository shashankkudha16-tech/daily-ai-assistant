package com.dailyai.assistant.service;

import com.dailyai.assistant.exception.BadRequestException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProgressAttachmentExtractorTest {
    private final ProgressAttachmentExtractor extractor = new ProgressAttachmentExtractor();

    @Test
    void extractsUtf8Text() {
        byte[] text = "Learned: 日本語 and APIs".getBytes(StandardCharsets.UTF_8);
        var result = extractor.extract(new MockMultipartFile("file", "notes.txt", "text/plain", text));
        assertThat(result.type()).isEqualTo("text/plain");
        assertThat(result.text()).contains("日本語 and APIs");
    }

    @Test
    void extractsPdfTextWithoutExecutingDocumentContent() throws Exception {
        byte[] pdf;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            try (PDPageContentStream stream = new PDPageContentStream(document, document.getPage(0))) {
                stream.beginText();
                stream.setFont(PDType1Font.HELVETICA, 12);
                stream.newLineAtOffset(50, 700);
                stream.showText("Reviewed PDF notes");
                stream.endText();
            }
            document.save(out);
            pdf = out.toByteArray();
        }
        assertThat(extractor.extract(new MockMultipartFile("file", "notes.pdf", "application/pdf", pdf)).text())
                .contains("Reviewed PDF notes");
    }

    @Test
    void extractsDocxParagraphs() throws Exception {
        byte[] docx;
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("Built a small REST API");
            document.write(out);
            docx = out.toByteArray();
        }
        assertThat(extractor.extract(new MockMultipartFile("file", "notes.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", docx)).text())
                .contains("Built a small REST API");
    }

    @Test
    void rejectsMismatchedContentAndOversizedUploads() {
        assertThatThrownBy(() -> extractor.extract(new MockMultipartFile("file", "notes.pdf", "application/pdf",
                "not a pdf".getBytes(StandardCharsets.UTF_8)))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> extractor.extract(new MockMultipartFile("file", "large.txt", "text/plain",
                new byte[(int) ProgressAttachmentExtractor.MAX_FILE_BYTES + 1]))).isInstanceOf(BadRequestException.class);
    }
}
