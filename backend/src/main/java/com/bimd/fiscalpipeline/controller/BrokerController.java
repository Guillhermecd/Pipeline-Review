package com.bimd.fiscalpipeline.controller;

import com.bimd.fiscalpipeline.domain.dto.BrokerConfigResponse;
import com.bimd.fiscalpipeline.domain.dto.BrokerSwitchRequest;
import com.bimd.fiscalpipeline.service.broker.BrokerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/broker")
@RequiredArgsConstructor
public class BrokerController {

    private final BrokerService brokerService;

    @GetMapping
    public BrokerConfigResponse current() {
        return brokerService.current();
    }

    @PutMapping
    public BrokerConfigResponse switchTo(@Valid @RequestBody BrokerSwitchRequest request) {
        return brokerService.switchTo(request.kind());
    }
}
