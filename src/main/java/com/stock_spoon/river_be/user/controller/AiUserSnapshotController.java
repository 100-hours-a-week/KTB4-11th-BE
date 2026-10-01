package com.stock_spoon.river_be.user.controller;

import com.stock_spoon.river_be.user.dto.AiUserSnapshotResponse;
import com.stock_spoon.river_be.user.service.AiUserSnapshotService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AiUserSnapshotController {
    private final AiUserSnapshotService service;

    public AiUserSnapshotController(AiUserSnapshotService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/users/ai-server")
    public AiUserSnapshotResponse snapshot() {
        return new AiUserSnapshotResponse(service.snapshot());
    }
}
