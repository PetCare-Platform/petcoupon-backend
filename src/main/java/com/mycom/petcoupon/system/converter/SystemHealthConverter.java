package com.mycom.petcoupon.system.converter;

import org.springframework.stereotype.Component;

import com.mycom.petcoupon.system.dto.res.ComponentHealthResponse;
import com.mycom.petcoupon.system.dto.res.SystemHealthResponse;
import com.mycom.petcoupon.system.service.SystemHealthService.SystemHealthSnapshot;

@Component
public class SystemHealthConverter {

    public SystemHealthResponse toResponse(SystemHealthSnapshot snapshot) {
        return SystemHealthResponse.builder()
                .overallStatus(snapshot.overallStatus())
                .components(snapshot.componentStatuses().entrySet().stream()
                        .map(entry -> ComponentHealthResponse.builder()
                                .name(entry.getKey())
                                .status(entry.getValue())
                                .build())
                        .toList())
                .build();
    }
}
