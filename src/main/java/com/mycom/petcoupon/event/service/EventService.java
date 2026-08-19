package com.mycom.petcoupon.event.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mycom.petcoupon.event.converter.EventConverter;
import com.mycom.petcoupon.event.dto.req.EventCreateRequest;
import com.mycom.petcoupon.event.dto.res.EventCreateResponse;
import com.mycom.petcoupon.event.entity.Event;
import com.mycom.petcoupon.event.repository.EventRepository;
import com.mycom.petcoupon.global.common.code.CommonErrorCode;
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
	private final EventConverter eventConverter;

	@Transactional
	public EventCreateResponse createEvent(EventCreateRequest request) {
		validatePeriod(request);

		AppUser createdBy = findActiveAdmin();
		Event event = eventConverter.toEntity(request, createdBy);
		Event savedEvent = eventRepository.save(event);

		return eventConverter.toResponse(savedEvent);
	}

	private void validatePeriod(EventCreateRequest request) {
		if (!request.closeAt().isAfter(request.openAt())) {
			throw new GeneralException(CommonErrorCode.BAD_REQUEST);
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
			.orElseThrow(() -> new GeneralException(CommonErrorCode.INTERNAL_SERVER_ERROR));
	}

}
