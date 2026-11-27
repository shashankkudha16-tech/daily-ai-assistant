package com.dailyai.assistant.controller;

import com.dailyai.assistant.dto.HistoryDto;
import com.dailyai.assistant.dto.ProfileDto;
import com.dailyai.assistant.dto.ProfileUpdateRequest;
import com.dailyai.assistant.dto.ProfilePreferencesRequest;
import com.dailyai.assistant.dto.ProfileCareerRequest;
import com.dailyai.assistant.service.ProfileService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    private final ProfileService profileService;

    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    @GetMapping
    public ProfileDto profile() {
        return profileService.profile();
    }

    @PutMapping
    public ProfileDto updateName(@Valid @RequestBody ProfileUpdateRequest request) {
        return profileService.updateName(request.name());
    }

    @PutMapping("/preferences")
    public ProfileDto updatePreferences(@Valid @RequestBody ProfilePreferencesRequest request) {
        return profileService.updatePreferences(request);
    }

    @PutMapping("/career")
    public ProfileDto updateCareerContext(@Valid @RequestBody ProfileCareerRequest request) {
        return profileService.updateCareerContext(request);
    }

    @GetMapping("/history")
    public List<HistoryDto> history(@RequestParam(defaultValue = "20") int limit) {
        return profileService.history(limit);
    }
}
