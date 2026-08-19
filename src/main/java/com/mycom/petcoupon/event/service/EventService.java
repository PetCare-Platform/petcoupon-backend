package com.mycom.petcoupon.event.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mycom.petcoupon.event.dto.EventCreateRequest;
import com.mycom.petcoupon.event.dto.EventCreateResponse;
import com.mycom.petcoupon.event.entity.Event;
import com.mycom.petcoupon.event.exception.EventErrorCode;
import com.mycom.petcoupon.event.repository.EventRepository;
import com.mycom.petcoupon.global.common.exception.GeneralException;
import com.mycom.petcoupon.user.entity.AppUser;
import com.mycom.petcoupon.user.entity.enums.UserRole;
import com.mycom.petcoupon.user.entity.enums.UserStatus;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class EventService {
	private final EventRepository eventRepository;
	private final EntityManager entityManager;

	@Transactional
	public EventCreateResponse createEvent(EventCreateRequest request) {
		validatePeriod(request);

		AppUser createdBy = findActiveAdmin();
		Event event = Event.builder()
				.createdBy(createdBy)
				.name(request.name())
				.description(request.description())
				.openAt(request.openAt())
				.closeAt(request.closeAt())
				.build();

		return EventCreateResponse.from(eventRepository.save(event));
	}

	private void validatePeriod(EventCreateRequest request) {
		if (!request.closeAt().isAfter(request.openAt())) {
			throw new GeneralException(EventErrorCode.INVALID_EVENT_PERIOD);
		}
	}

	private AppUser findActiveAdmin() {
		return entityManager.createQuery(
				"""
				select u
				from AppUser u
				where u.role = :role
				  and u.status = :status
				order by u.userId
				""",
				AppUser.class
			)
			.setParameter("role", UserRole.ROLE_ADMIN)
			.setParameter("status", UserStatus.ACTIVE)
			.setMaxResults(1)
			.getResultStream()
			.findFirst()
			.orElseThrow(() -> new GeneralException(EventErrorCode.ADMIN_USER_NOT_FOUND));
	}

}
