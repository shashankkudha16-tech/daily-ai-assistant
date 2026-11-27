package com.dailyai.assistant.controller;

import com.dailyai.assistant.dto.PersonalPlanItemDto;
import com.dailyai.assistant.dto.PersonalPlanStatusRequest;
import com.dailyai.assistant.service.PersonalLearningPlanService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/learning/personal-plan")
public class PersonalLearningPlanController {
    private final PersonalLearningPlanService service;
    public PersonalLearningPlanController(PersonalLearningPlanService service){this.service=service;}
    @GetMapping("/today") public List<PersonalPlanItemDto> today(){return service.today();}
    @PostMapping("/today") public List<PersonalPlanItemDto> createOrLoadToday(){return service.createOrLoadToday();}
    @GetMapping("/history") public List<PersonalPlanItemDto> history(){return service.history();}
    @PatchMapping("/{id}/status") public PersonalPlanItemDto status(@PathVariable Long id,@Valid @RequestBody PersonalPlanStatusRequest request){return service.setStatus(id,request.status());}
}
