package com.mycom.petcoupon.monitoring.dto.req;

import jakarta.validation.constraints.NotNull;

public record MonitoringSettingsUpdateRequest(
        @NotNull(message = "streamEnabled 값은 필수입니다.")
        Boolean streamEnabled
) {
}
