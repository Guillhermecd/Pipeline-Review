package com.bimd.fiscalpipeline.domain.mapper;

import com.bimd.fiscalpipeline.domain.dto.UserResponse;
import com.bimd.fiscalpipeline.domain.model.User;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface UserMapper {

    UserResponse toResponse(User user);
}
