package com.bimd.fiscalpipeline.config;

import com.bimd.fiscalpipeline.domain.model.User;
import com.bimd.fiscalpipeline.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Seeds the first admin account on boot so login works with zero manual setup. */
@Component
@RequiredArgsConstructor
public class InitialUserSeeder implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.initial-user.email}")
    private String email;

    @Value("${app.initial-user.password}")
    private String password;

    @Value("${app.initial-user.name}")
    private String name;

    @Override
    public void run(String... args) {
        if (userRepository.existsByEmail(email)) {
            return;
        }
        User user = new User();
        user.setName(name);
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(password));
        userRepository.save(user);
    }
}
