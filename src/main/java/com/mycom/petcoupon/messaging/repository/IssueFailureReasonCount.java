package com.mycom.petcoupon.messaging.repository;

import com.mycom.petcoupon.messaging.entity.enums.IssueFailureReason;

// 실패 사유 분류 집계(#195)용 인터페이스 프로젝션. failureReason은 이 컬럼이 생기기 전 DLQ 행에서
// null일 수 있다(IssueMessage.failureReason 주석 참고).
public interface IssueFailureReasonCount {

    IssueFailureReason getFailureReason();

    Long getCount();
}
