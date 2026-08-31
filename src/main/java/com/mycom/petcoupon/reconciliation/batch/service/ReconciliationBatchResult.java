package com.mycom.petcoupon.reconciliation.batch.service;

import java.util.List;
import java.util.Map;

import com.mycom.petcoupon.reconciliation.entity.ReconciliationReport;
import com.mycom.petcoupon.reconciliation.entity.VerificationDetail;
import com.mycom.petcoupon.reconciliation.entity.enums.VerificationErrorType;

// verification_detail 전체를 JOIN FETCH로 한 번에 끌고 오지 않기 위해, Controller/Converter가
// 실제로 쓰는 만큼만(전체 건수, 응답용 상위 N건, 로그용 타입별 집계) 따로 담아 넘기는 캐리어.
public record ReconciliationBatchResult(
        ReconciliationReport report,
        long verificationDetailCount,
        List<VerificationDetail> topVerificationDetails,
        Map<VerificationErrorType, Long> verificationDetailCountByType
) {
}
