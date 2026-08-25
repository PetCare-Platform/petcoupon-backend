package com.mycom.petcoupon.reconciliation.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mycom.petcoupon.reconciliation.entity.VerificationDetail;

public interface VerificationDetailRepository extends JpaRepository<VerificationDetail, Long> {
}
