package com.dailyai.assistant.service;

import com.dailyai.assistant.dto.AuthUserDto;
import com.dailyai.assistant.dto.RegisterRequest;
import com.dailyai.assistant.entity.Role;
import com.dailyai.assistant.entity.User;
import com.dailyai.assistant.exception.BadRequestException;
import com.dailyai.assistant.exception.ConflictException;
import com.dailyai.assistant.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final GuestAccountMigrationService guestAccountMigrationService;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                       GuestAccountMigrationService guestAccountMigrationService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.guestAccountMigrationService = guestAccountMigrationService;
    }

    @Transactional
    public User register(RegisterRequest request) {
        if (!request.password().equals(request.confirmPassword())) {
            throw new BadRequestException("Passwords do not match");
        }
        String email = normalizeEmail(request.email());
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new ConflictException("An account with this email already exists");
        }

        User user = new User(request.name().trim(), email);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole(Role.USER);
        user.setEnabled(true);
        user.setPasswordChangedAt(Instant.now());
        try {
            return userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException ex) {
            // Handles the race between the uniqueness check and insert.
            throw new ConflictException("An account with this email already exists");
        }
    }

    @Transactional
    public RegistrationResult registerWithGuest(RegisterRequest request, String guestId) {
        User user = register(request);
        GuestAccountMigrationService.Status status = guestAccountMigrationService.migrate(user.getId(), guestId);
        return new RegistrationResult(user, status);
    }

    public record RegistrationResult(User user, GuestAccountMigrationService.Status migrationStatus) { }

    public AuthUserDto toDto(User user) {
        return new AuthUserDto(user.getId(), user.getName(), user.getEmail(), user.getRole().name());
    }

    public static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
