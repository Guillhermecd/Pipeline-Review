package com.bimd.fiscalpipeline.controller;

import com.bimd.fiscalpipeline.service.event.EventStreamService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Live state of the pipeline as server-sent events. {@code EventSource} cannot send headers, so
 * this is the one route that also accepts the JWT in {@code ?access_token=}.
 */
@RestController
@RequestMapping("/api/v1/eventos")
@RequiredArgsConstructor
public class EventStreamController {

    private final EventStreamService eventStreamService;

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return eventStreamService.connect();
    }
}
