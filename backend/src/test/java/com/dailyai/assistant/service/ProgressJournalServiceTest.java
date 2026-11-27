package com.dailyai.assistant.service;

import com.dailyai.assistant.dto.ProgressJournalRequest;
import com.dailyai.assistant.entity.User;
import com.dailyai.assistant.entity.UserProgressEntry;
import com.dailyai.assistant.repository.UserProgressEntryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ProgressJournalServiceTest {
    private final UserProgressEntryRepository repository = mock(UserProgressEntryRepository.class);
    private final CurrentUserService currentUser = mock(CurrentUserService.class);
    private final ProgressAttachmentExtractor extractor = mock(ProgressAttachmentExtractor.class);
    private final ProgressJournalService service = new ProgressJournalService(repository, currentUser, extractor);

    @Test
    void updatesTheCurrentUsersExistingDateEntryInsteadOfCreatingAnother() {
        User user = new User("Learner", "learner@example.test");
        user.setId(7L);
        UserProgressEntry existing = entry(user, LocalDate.parse("2026-11-25"));
        when(currentUser.getCurrentUser()).thenReturn(user);
        when(repository.findByUserIdAndEntryDate(7L, existing.getEntryDate())).thenReturn(Optional.of(existing));
        when(repository.save(existing)).thenReturn(existing);

        var saved = service.save(existing.getEntryDate(), new ProgressJournalRequest(
                "Read about PostgreSQL", "Wrote a query", "", "Joins were difficult", "Practice joins"));

        assertThat(saved.date()).isEqualTo(existing.getEntryDate());
        assertThat(saved.learned()).isEqualTo("Read about PostgreSQL");
        verify(repository).findByUserIdAndEntryDate(7L, existing.getEntryDate());
        verify(repository).save(existing);
        verify(repository, never()).save(argThat(candidate -> candidate != existing));
    }

    @Test
    void uploadMetadataAndExtractedTextAreStoredWithoutTheOriginalFile() {
        User user = new User("Learner", "learner@example.test");
        user.setId(7L);
        LocalDate date = LocalDate.parse("2026-11-25");
        when(currentUser.getCurrentUser()).thenReturn(user);
        when(repository.findByUserIdAndEntryDate(7L, date)).thenReturn(Optional.empty());
        when(extractor.extract(any())).thenReturn(new ProgressAttachmentExtractor.Extracted("text/plain", "Extracted notes"));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var saved = service.attach(date, new MockMultipartFile("file", "notes.txt", "text/plain", "ignored".getBytes()));

        assertThat(saved.attachmentName()).isEqualTo("notes.txt");
        assertThat(saved.attachmentType()).isEqualTo("text/plain");
        assertThat(saved.attachmentText()).isEqualTo("Extracted notes");
        verify(repository).findByUserIdAndEntryDate(7L, date);
    }

    private UserProgressEntry entry(User user, LocalDate date) {
        UserProgressEntry entry = new UserProgressEntry();
        entry.setUser(user);
        entry.setEntryDate(date);
        return entry;
    }
}
