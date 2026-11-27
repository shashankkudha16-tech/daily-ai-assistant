package com.dailyai.assistant.controller;

import com.dailyai.assistant.dto.MentorReportDto;
import com.dailyai.assistant.service.PersonalMentorService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/mentor")
public class MentorController {
    private final PersonalMentorService service;
    public MentorController(PersonalMentorService service) { this.service = service; }

    @GetMapping("/review")
    public MentorReportDto current() { return service.current(); }

    @GetMapping("/history")
    public List<MentorReportDto> history() { return service.history(); }

    @PostMapping("/review")
    public MentorReportDto generate() { return service.generate(); }
}
