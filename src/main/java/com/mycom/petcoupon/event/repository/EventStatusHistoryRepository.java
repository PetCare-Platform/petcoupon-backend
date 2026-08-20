package com.mycom.petcoupon.event.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mycom.petcoupon.event.entity.EventStatusHistory;

public interface EventStatusHistoryRepository extends JpaRepository<EventStatusHistory, Long> {
}
