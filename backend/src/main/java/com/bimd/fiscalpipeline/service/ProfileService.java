package com.bimd.fiscalpipeline.service;

import com.bimd.fiscalpipeline.domain.dto.UpdateProfileRequest;
import com.bimd.fiscalpipeline.domain.dto.UserResponse;
import com.bimd.fiscalpipeline.domain.mapper.UserMapper;
import com.bimd.fiscalpipeline.domain.model.User;
import com.bimd.fiscalpipeline.exception.BusinessException;
import com.bimd.fiscalpipeline.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProfileService {

    private final UserRepository userRepository;
    private final UserMapper userMapper;

    public UserResponse getProfile(String email) {
        return userMapper.toResponse(findByEmail(email));
    }

    public UserResponse updateProfile(String email, UpdateProfileRequest request) {
        User user = findByEmail(email);
        user.setName(request.name());
        userRepository.save(user);
        return userMapper.toResponse(user);
    }

    private User findByEmail(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "User not found"));
    }
}
