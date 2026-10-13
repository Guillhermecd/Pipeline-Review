package com.bimd.fiscalpipeline.controller;

import com.bimd.fiscalpipeline.domain.dto.UpdateProfileRequest;
import com.bimd.fiscalpipeline.domain.dto.UserResponse;
import com.bimd.fiscalpipeline.service.ProfileService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/profile")
@RequiredArgsConstructor
public class ProfileController {

    private final ProfileService profileService;

    @GetMapping
    public UserResponse getProfile(@AuthenticationPrincipal UserDetails principal) {
        return profileService.getProfile(principal.getUsername());
    }

    @PatchMapping
    public UserResponse updateProfile(
            @AuthenticationPrincipal UserDetails principal, @Valid @RequestBody UpdateProfileRequest request) {
        return profileService.updateProfile(principal.getUsername(), request);
    }
}
