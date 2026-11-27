package com.dailyai.assistant.service;

import com.dailyai.assistant.exception.BadRequestException;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Component
public class ProgressAttachmentExtractor {
    public static final long MAX_FILE_BYTES = 5L * 1024 * 1024;
    public static final int MAX_EXTRACTED_CHARS = 80_000;

    public record Extracted(String type, String text) {}

    public Extracted extract(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new BadRequestException("Choose a non-empty PDF, DOCX or TXT file.");
        if (file.getSize() > MAX_FILE_BYTES) throw new BadRequestException("Files must be 5 MB or smaller.");
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String lower = name.toLowerCase(Locale.ROOT);
        try {
            byte[] bytes = file.getBytes();
            String text;
            String type;
            if (lower.endsWith(".txt")) {
                text = decodeUtf8(bytes);
                type = "text/plain";
            } else if (lower.endsWith(".pdf") && startsWith(bytes, "%PDF-".getBytes(StandardCharsets.US_ASCII))) {
                try (PDDocument document = PDDocument.load(new ByteArrayInputStream(bytes), MemoryUsageSetting.setupTempFileOnly())) {
                    if (document.isEncrypted()) throw new BadRequestException("Encrypted PDFs are not supported.");
                    text = new PDFTextStripper().getText(document);
                }
                type = "application/pdf";
            } else if (lower.endsWith(".docx") && isDocx(bytes)) {
                try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
                    StringBuilder extracted = new StringBuilder();
                    for (XWPFParagraph paragraph : document.getParagraphs()) {
                        if (!paragraph.getText().isBlank()) extracted.append(paragraph.getText()).append('\n');
                        if (extracted.length() > MAX_EXTRACTED_CHARS) break;
                    }
                    text = extracted.toString();
                }
                type = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            } else {
                throw new BadRequestException("File extension and content must match a supported PDF, DOCX or TXT format.");
            }
            text = sanitize(text);
            if (text.isBlank()) throw new BadRequestException("No readable text was found in the file.");
            if (text.length() > MAX_EXTRACTED_CHARS) text = text.substring(0, MAX_EXTRACTED_CHARS);
            return new Extracted(type, text);
        } catch (BadRequestException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new BadRequestException("The file could not be read safely. Check that it is a valid, unencrypted document.");
        }
    }

    private static boolean isDocx(byte[] bytes) throws IOException {
        boolean contentTypes = false;
        boolean documentXml = false;
        long total = 0;
        ZipSecureFile.setMaxEntrySize(10L * 1024 * 1024);
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                long entryBytes = 0;
                if ("[Content_Types].xml".equals(entry.getName())) contentTypes = true;
                if ("word/document.xml".equals(entry.getName())) documentXml = true;
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    entryBytes += read;
                    total += read;
                    if (entryBytes > 10L * 1024 * 1024 || total > 20L * 1024 * 1024) return false;
                }
            }
        }
        return contentTypes && documentXml;
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            String text = decoder.decode(ByteBuffer.wrap(bytes)).toString();
            return text.startsWith("\uFEFF") ? text.substring(1) : text;
        } catch (CharacterCodingException e) {
            throw new BadRequestException("TXT uploads must use valid UTF-8 encoding.");
        }
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (bytes[i] != prefix[i]) return false;
        return true;
    }

    private static String sanitize(String text) {
        return text == null ? "" : text.replace("\u0000", "").replaceAll("[\\p{Cc}&&[^\\r\\n\\t]]", "").trim();
    }
}
