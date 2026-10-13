package com.bimd.fiscalpipeline.service;

import com.bimd.fiscalpipeline.domain.dto.AuthResponse;
import com.bimd.fiscalpipeline.domain.dto.LoginRequest;
import com.bimd.fiscalpipeline.domain.dto.RegisterRequest;
import com.bimd.fiscalpipeline.domain.mapper.UserMapper;
import com.bimd.fiscalpipeline.domain.model.User;
import com.bimd.fiscalpipeline.exception.BusinessException;
import com.bimd.fiscalpipeline.repository.UserRepository;
import com.bimd.fiscalpipeline.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;

    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new BusinessException(HttpStatus.CONFLICT, "Email already registered");
        }
        User user = new User();
        user.setName(request.name());
        user.setEmail(request.email());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        userRepository.save(user);
        String token = jwtService.generateToken(user.getEmail());
        return new AuthResponse(token, userMapper.toResponse(user));
    }

    public AuthResponse login(LoginRequest request) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.email(), request.password()));
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));
        String token = jwtService.generateToken(user.getEmail());
        return new AuthResponse(token, userMapper.toResponse(user));
    }
}
