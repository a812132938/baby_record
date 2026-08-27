package com.babyrecord.controller;

import com.babyrecord.auth.DeviceAuthInterceptor;
import com.babyrecord.auth.DeviceSessionPrincipal;
import com.babyrecord.dto.AiConversationDetail;
import com.babyrecord.dto.AiConversationListResponse;
import com.babyrecord.dto.AiCreateConversationRequest;
import com.babyrecord.dto.AiQuestionRequest;
import com.babyrecord.dto.AiSnapshotResponse;
import com.babyrecord.service.AiConversationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/babies/{babyId}/ai/conversations")
public class AiConversationController {
    private final AiConversationService service;

    public AiConversationController(AiConversationService service) {
        this.service = service;
    }

    @GetMapping
    public AiConversationListResponse list(
            @PathVariable long babyId,
            @RequestAttribute(DeviceAuthInterceptor.REQUEST_ATTRIBUTE) DeviceSessionPrincipal principal) {
        return service.list(babyId, principal);
    }

    @PostMapping
    public ResponseEntity<AiConversationDetail> create(
            @PathVariable long babyId,
            @RequestAttribute(DeviceAuthInterceptor.REQUEST_ATTRIBUTE) DeviceSessionPrincipal principal,
            @Valid @RequestBody AiCreateConversationRequest request) {
        return ResponseEntity.accepted().body(service.create(babyId, principal, request));
    }

    @GetMapping("/{conversationId}")
    public AiConversationDetail detail(
            @PathVariable long babyId,
            @PathVariable long conversationId,
            @RequestAttribute(DeviceAuthInterceptor.REQUEST_ATTRIBUTE) DeviceSessionPrincipal principal) {
        return service.get(babyId, conversationId, principal);
    }

    @GetMapping("/{conversationId}/snapshots/{snapshotId}")
    public AiSnapshotResponse snapshot(
            @PathVariable long babyId,
            @PathVariable long conversationId,
            @PathVariable long snapshotId,
            @RequestAttribute(DeviceAuthInterceptor.REQUEST_ATTRIBUTE) DeviceSessionPrincipal principal) {
        return service.getSnapshot(babyId, conversationId, snapshotId, principal);
    }

    @GetMapping(value = "/{conversationId}/messages/{messageId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(
            @PathVariable long babyId,
            @PathVariable long conversationId,
            @PathVariable long messageId,
            @RequestAttribute(DeviceAuthInterceptor.REQUEST_ATTRIBUTE) DeviceSessionPrincipal principal) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, no-transform")
                .header("X-Accel-Buffering", "no")
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(service.stream(babyId, conversationId, messageId, principal));
    }

    @PostMapping("/{conversationId}/messages")
    public ResponseEntity<AiConversationDetail> question(
            @PathVariable long babyId,
            @PathVariable long conversationId,
            @RequestAttribute(DeviceAuthInterceptor.REQUEST_ATTRIBUTE) DeviceSessionPrincipal principal,
            @Valid @RequestBody AiQuestionRequest request) {
        return ResponseEntity.accepted().body(service.question(babyId, conversationId, principal, request));
    }

    @PostMapping("/{conversationId}/retry")
    public ResponseEntity<AiConversationDetail> retry(
            @PathVariable long babyId,
            @PathVariable long conversationId,
            @RequestAttribute(DeviceAuthInterceptor.REQUEST_ATTRIBUTE) DeviceSessionPrincipal principal) {
        return ResponseEntity.accepted().body(service.retry(babyId, conversationId, principal));
    }

    @DeleteMapping("/{conversationId}")
    public ResponseEntity<Void> archive(
            @PathVariable long babyId,
            @PathVariable long conversationId,
            @RequestAttribute(DeviceAuthInterceptor.REQUEST_ATTRIBUTE) DeviceSessionPrincipal principal) {
        service.archive(babyId, conversationId, principal);
        return ResponseEntity.noContent().build();
    }
}
