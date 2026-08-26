package com.mycom.petcoupon.event.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mycom.petcoupon.event.converter.EventConverter;
import com.mycom.petcoupon.event.dto.req.EventCreateRequest;
import com.mycom.petcoupon.event.dto.req.EventStatusUpdateRequest;
import com.mycom.petcoupon.event.dto.req.EventUpdateRequest;
import com.mycom.petcoupon.event.dto.res.EventCreateResponse;
import com.mycom.petcoupon.event.dto.res.EventDetailResponse;
import com.mycom.petcoupon.event.dto.res.EventStatusResponse;
import com.mycom.petcoupon.event.dto.res.EventUpdateResponse;
import com.mycom.petcoupon.event.entity.Event;
import com.mycom.petcoupon.event.entity.EventStatusHistory;
import com.mycom.petcoupon.event.entity.enums.ActorType;
import com.mycom.petcoupon.event.entity.enums.EventHistoryStatus;
import com.mycom.petcoupon.event.entity.enums.EventStatus;
import com.mycom.petcoupon.event.exception.EventErrorCode;
import com.mycom.petcoupon.event.repository.EventRepository;
import com.mycom.petcoupon.event.repository.EventStatusHistoryRepository;
import com.mycom.petcoupon.global.common.code.CommonErrorCode;
import com.mycom.petcoupon.global.common.exception.GeneralException;
import com.mycom.petcoupon.user.entity.AppUser;
import com.mycom.petcoupon.user.entity.enums.UserRole;
import com.mycom.petcoupon.user.entity.enums.UserStatus;
import com.mycom.petcoupon.user.repository.AppUserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class EventServiceImpl implements EventService {
	private final EventRepository eventRepository;
	private final EventStatusHistoryRepository eventStatusHistoryRepository;
	private final AppUserRepository appUserRepository;
	private final EventConverter eventConverter;

	@Override
	@Transactional
	public EventCreateResponse createEvent(EventCreateRequest request) {
		validatePeriod(request);

		AppUser createdBy = findActiveAdmin();
		Event event = eventConverter.toEntity(request, createdBy);
		Event savedEvent = eventRepository.save(event);
		EventStatusHistory initialHistory = EventStatusHistory.builder()
				.event(savedEvent)
				.fromStatus(EventHistoryStatus.NONE)
				.toStatus(EventHistoryStatus.SCHEDULED)
				.actorType(ActorType.ADMIN)
				.actorId(createdBy.getUserId())
				.reason("이벤트 생성")
				.build();
		eventStatusHistoryRepository.save(initialHistory);

		return eventConverter.toCreateResponse(savedEvent);
	}

	@Override
	@Transactional(readOnly = true)
	public EventDetailResponse getEventDetail(Long eventId) {
		Event event = eventRepository.findById(eventId)
				.orElseThrow(() -> new GeneralException(EventErrorCode.EVENT_NOT_FOUND));

		return eventConverter.toDetailResponse(event);
	}

	@Override
	@Transactional(readOnly = true)
	public EventStatusResponse getEventStatus(Long eventId) {
		EventStatus status = eventRepository.findStatusByEventId(eventId)
				.orElseThrow(() -> new GeneralException(EventErrorCode.EVENT_NOT_FOUND));

		return eventConverter.toStatusResponse(eventId, status);
	}

	@Override
	@Transactional
	public EventUpdateResponse updateEvent(Long eventId, EventUpdateRequest request) {
		Event event = eventRepository.findById(eventId)
				.orElseThrow(() -> new GeneralException(EventErrorCode.EVENT_NOT_FOUND));

		// 기간은 한쪽만 보낼 수 있으므로 기존 값과 합쳐 검증한 뒤, 다른 필드보다 먼저 반영한다
		if (request.openAt() != null || request.closeAt() != null) {
			LocalDateTime openAt = request.openAt() != null ? request.openAt() : event.getOpenAt();
			LocalDateTime closeAt = request.closeAt() != null ? request.closeAt() : event.getCloseAt();
			validatePeriod(openAt, closeAt);

			event.updatePeriod(openAt, closeAt);
		}
		if (request.name() != null) {
			event.updateName(request.name());
		}
		if (request.description() != null) {
			// 빈 문자열은 "설명 비우기"이므로 null로 저장한다
			event.updateDescription(request.description().isBlank() ? null : request.description());
		}

		return eventConverter.toUpdateResponse(event);
	}

	@Override
	@Transactional
	public EventUpdateResponse updateEventStatus(Long eventId, EventStatusUpdateRequest request) {
		Event event = eventRepository.findById(eventId)
				.orElseThrow(() -> new GeneralException(EventErrorCode.EVENT_NOT_FOUND));

		EventStatus fromStatus = event.getStatus();
		EventStatus toStatus = request.status();
		if (fromStatus == toStatus) {
			throw new GeneralException(EventErrorCode.SAME_EVENT_STATUS);
		}
		if (!fromStatus.canTransitionTo(toStatus)) {
			throw new GeneralException(EventErrorCode.INVALID_EVENT_STATUS_TRANSITION);
		}

		AppUser admin = findActiveAdmin();

		int updatedRows = eventRepository.updateStatusIfMatches(eventId, fromStatus, toStatus);
		if (updatedRows == 0) {
			throw new GeneralException(EventErrorCode.EVENT_STATUS_CONFLICT);
		}
		event.updateStatus(toStatus);

		EventStatusHistory history = EventStatusHistory.builder()
				.event(event)
				.fromStatus(EventHistoryStatus.valueOf(fromStatus.name()))
				.toStatus(EventHistoryStatus.valueOf(toStatus.name()))
				.actorType(ActorType.ADMIN)
				.actorId(admin.getUserId())
				.reason(request.reason())
				.build();
		eventStatusHistoryRepository.save(history);

		return eventConverter.toUpdateResponse(event);
	}

	private void validatePeriod(EventCreateRequest request) {
		validatePeriod(request.openAt(), request.closeAt());
	}

	private void validatePeriod(LocalDateTime openAt, LocalDateTime closeAt) {
		if (!closeAt.isAfter(openAt)) {
			throw new GeneralException(EventErrorCode.INVALID_EVENT_PERIOD);
		}
	}

	private AppUser findActiveAdmin() {
		// TODO: ADMIN_AUTH_CODE 기반 인증 API 구현 후 임의 관리자 조회를 제거하고 인증된 관리자 ID를 사용한다.
		return appUserRepository
				.findFirstByRoleAndStatusOrderByUserIdAsc(UserRole.ROLE_ADMIN, UserStatus.ACTIVE)
				.orElseThrow(() -> new GeneralException(CommonErrorCode.INTERNAL_SERVER_ERROR));
	}
}
