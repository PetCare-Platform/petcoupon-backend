package com.mycom.petcoupon.idempotency.repository;

// 실패 사유 분류 집계(#195)용 인터페이스 프로젝션.
public interface IdempotencyRejectionCounts {

    long getSoldOut();

    long getAlreadyIssued();
}
