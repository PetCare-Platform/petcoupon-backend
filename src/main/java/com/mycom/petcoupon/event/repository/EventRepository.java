package com.mycom.petcoupon.event.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mycom.petcoupon.event.entity.Event;

public interface EventRepository extends JpaRepository<Event, Long> {
}
