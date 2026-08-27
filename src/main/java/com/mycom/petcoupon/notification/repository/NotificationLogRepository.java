package com.mycom.petcoupon.notification.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mycom.petcoupon.notification.entity.NotificationLog;

public interface NotificationLogRepository extends JpaRepository<NotificationLog, Long> {
}
