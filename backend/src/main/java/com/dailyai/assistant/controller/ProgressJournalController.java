package com.dailyai.assistant.controller;

import com.dailyai.assistant.dto.ProgressJournalDto;
import com.dailyai.assistant.dto.ProgressJournalRequest;
import com.dailyai.assistant.service.ProgressJournalService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/progress")
public class ProgressJournalController {
    private final ProgressJournalService service;

    public ProgressJournalController(ProgressJournalService service) { this.service = service; }

    @GetMapping("/entries")
    public List<ProgressJournalDto> recent() { return service.recent(); }

    @GetMapping("/entries/{date}")
    public ProgressJournalDto get(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.get(date);
    }

    @PutMapping("/entries/{date}")
    public ProgressJournalDto save(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                   @Valid @RequestBody ProgressJournalRequest request) {
        return service.save(date, request);
    }

    @PostMapping(value = "/entries/{date}/attachment", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ProgressJournalDto upload(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                     @RequestPart("file") MultipartFile file) {
        return service.attach(date, file);
    }

    @DeleteMapping("/entries/{date}/attachment")
    public void removeAttachment(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        service.removeAttachment(date);
    }
}
