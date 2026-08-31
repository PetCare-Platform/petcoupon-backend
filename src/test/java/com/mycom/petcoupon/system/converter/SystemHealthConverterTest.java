package com.mycom.petcoupon.system.converter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.mycom.petcoupon.system.dto.res.SystemHealthResponse;
import com.mycom.petcoupon.system.service.SystemHealthService.SystemHealthSnapshot;

class SystemHealthConverterTest {

    private final SystemHealthConverter converter = new SystemHealthConverter();

    @Test
    void toResponse는_overall과_컴포넌트_맵을_리스트로_변환한다() {
        Map<String, String> componentStatuses = new LinkedHashMap<>();
        componentStatuses.put("db", "UP");
        componentStatuses.put("redis", "UP");
        SystemHealthSnapshot snapshot = new SystemHealthSnapshot("UP", componentStatuses);

        SystemHealthResponse response = converter.toResponse(snapshot);

        assertThat(response.overallStatus()).isEqualTo("UP");
        assertThat(response.components()).hasSize(2);
        assertThat(response.components())
                .extracting("name", "status")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("db", "UP"),
                        org.assertj.core.groups.Tuple.tuple("redis", "UP")
                );
    }
}
