package com.dailyai.assistant.service;

import com.dailyai.assistant.dto.ProgressJournalDto;
import com.dailyai.assistant.dto.ProgressJournalRequest;
import com.dailyai.assistant.entity.UserProgressEntry;
import com.dailyai.assistant.exception.ResourceNotFoundException;
import com.dailyai.assistant.repository.UserProgressEntryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Service
public class ProgressJournalService {
    private final UserProgressEntryRepository repository;
    private final CurrentUserService currentUserService;
    private final ProgressAttachmentExtractor attachmentExtractor;

    public ProgressJournalService(UserProgressEntryRepository repository, CurrentUserService currentUserService,
                                  ProgressAttachmentExtractor attachmentExtractor) {
        this.repository = repository;
        this.currentUserService = currentUserService;
        this.attachmentExtractor = attachmentExtractor;
    }

    @Transactional(readOnly = true)
    public List<ProgressJournalDto> recent() {
        return repository.findTop100ByUserIdOrderByEntryDateDesc(currentUserService.getCurrentUserId())
                .stream().map(ProgressJournalDto::from).toList();
    }

    @Transactional(readOnly = true)
    public ProgressJournalDto get(LocalDate date) {
        return repository.findByUserIdAndEntryDate(currentUserService.getCurrentUserId(), date)
                .map(ProgressJournalDto::from).orElse(null);
    }

    @Transactional
    public ProgressJournalDto save(LocalDate date, ProgressJournalRequest request) {
        var user = currentUserService.getCurrentUser();
        UserProgressEntry entry = repository.findByUserIdAndEntryDate(user.getId(), date).orElseGet(() -> {
            UserProgressEntry created = new UserProgressEntry();
            created.setUser(user);
            created.setEntryDate(date);
            return created;
        });
        entry.setLearned(clean(request.learned()));
        entry.setPracticed(clean(request.practiced()));
        entry.setBuilt(clean(request.built()));
        entry.setDifficulties(clean(request.difficulties()));
        entry.setTomorrowGoals(clean(request.tomorrowGoals()));
        entry.setUpdatedAt(Instant.now());
        return ProgressJournalDto.from(repository.save(entry));
    }

    @Transactional
    public ProgressJournalDto attach(LocalDate date, MultipartFile file) {
        var user = currentUserService.getCurrentUser();
        UserProgressEntry entry = repository.findByUserIdAndEntryDate(user.getId(), date).orElseGet(() -> {
            UserProgressEntry created = new UserProgressEntry();
            created.setUser(user);
            created.setEntryDate(date);
            return created;
        });
        ProgressAttachmentExtractor.Extracted extracted = attachmentExtractor.extract(file);
        String original = file.getOriginalFilename() == null ? "upload" : file.getOriginalFilename();
        String filename = original.replace('\\', '/');
        filename = filename.substring(filename.lastIndexOf('/') + 1);
        entry.setAttachmentName(filename.substring(0, Math.min(filename.length(), 255)));
        entry.setAttachmentType(extracted.type());
        entry.setAttachmentText(extracted.text());
        entry.setUpdatedAt(Instant.now());
        return ProgressJournalDto.from(repository.save(entry));
    }

    @Transactional
    public void removeAttachment(LocalDate date) {
        UserProgressEntry entry = repository.findByUserIdAndEntryDate(currentUserService.getCurrentUserId(), date)
                .orElseThrow(() -> new ResourceNotFoundException("Progress entry was not found"));
        entry.setAttachmentName(null);
        entry.setAttachmentType(null);
        entry.setAttachmentText(null);
        entry.setUpdatedAt(Instant.now());
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
}
