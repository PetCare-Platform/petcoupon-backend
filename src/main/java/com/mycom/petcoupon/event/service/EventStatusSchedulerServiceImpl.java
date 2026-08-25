package com.mycom.petcoupon.event.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mycom.petcoupon.event.entity.Event;
import com.mycom.petcoupon.event.entity.EventStatusHistory;
import com.mycom.petcoupon.event.entity.enums.ActorType;
import com.mycom.petcoupon.event.entity.enums.EventHistoryStatus;
import com.mycom.petcoupon.event.entity.enums.EventStatus;
import com.mycom.petcoupon.event.repository.EventRepository;
import com.mycom.petcoupon.event.repository.EventStatusHistoryRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventStatusSchedulerServiceImpl implements EventStatusSchedulerService {

	private final EventRepository eventRepository;
	private final EventStatusHistoryRepository eventStatusHistoryRepository;

	// 오픈 시각이 이미 지난 SCHEDULED 이벤트를 먼저 OPEN으로 올린 뒤, 같은 now 기준으로 종료 시각이 지난
	// OPEN 이벤트를 CLOSED로 내린다 — 배치가 한동안 못 돌고 재기동해도 한 번의 실행에서 SCHEDULED->CLOSED까지 이어진다.
	// 실제 실행 주기는 여기가 아니라 EventSchedulingRunner(전용 TaskScheduler)에서 등록한다.
	//
	// 여기서는 예외를 잡지 않는다. 스케줄러가 예외로 멈추지 않게 하는 건 EventSchedulingRunner가
	// 태스크 자체를 감싸서 처리한다 — 이 메서드 안에서 잡으면 @Transactional 경계(커넥션 획득 / 커밋)
	// 에서 난 예외를 놓치고, 예외를 삼키면 트랜잭션이 rollback-only로 마킹돼 커밋 시점에
	// UnexpectedRollbackException이 새로 터진다. 그냥 던져서 트랜잭션을 깨끗이 롤백시키고 다음 주기에
	// 재시도하게 둔다(updateStatusIfMatches가 조건부 UPDATE라 재시도가 안전하다).
	@Override
	@Transactional
	public void syncEventStatuses() {
		LocalDateTime now = LocalDateTime.now();

		int opened = openScheduledEvents(now);
		int closed = closeOpenEvents(now);

		if (opened > 0 || closed > 0) {
			log.info("이벤트 상태 전환 스케줄러 완료. OPEN 전환={}건, CLOSED 전환={}건", opened, closed);
		}
	}

	private int openScheduledEvents(LocalDateTime now) {
		List<Event> targets = eventRepository.findByStatusAndOpenAtLessThanEqual(EventStatus.SCHEDULED, now);
		return transition(targets, EventStatus.SCHEDULED, EventStatus.OPEN, "오픈 시각 도달");
	}

	private int closeOpenEvents(LocalDateTime now) {
		List<Event> targets = eventRepository.findByStatusAndCloseAtLessThanEqual(EventStatus.OPEN, now);
		return transition(targets, EventStatus.OPEN, EventStatus.CLOSED, "종료 시각 도달");
	}

	// 더티체킹 대신 관리자 API(EventServiceImpl)와 동일한 원자적 조건부 업데이트를 쓴다.
	// 그 사이 다른 주체가 이미 상태를 바꿔놨으면 0건이 반환되는데, 그러면 이력도 안 남기고 건너뛴다 —
	// 그냥 덮어쓰면 동시 변경을 무시하게 되고, EventStatusHistory의 (event_id, from_status, to_status)
	// 유니크 제약과 충돌해서 그 틱에서 처리 중이던 다른 이벤트들까지 함께 롤백될 수 있다.
	private int transition(List<Event> events, EventStatus fromStatus, EventStatus toStatus, String reason) {
		List<EventStatusHistory> histories = new ArrayList<>();

		for (Event event : events) {
			int updatedRows = eventRepository.updateStatusIfMatches(event.getEventId(), fromStatus, toStatus);
			if (updatedRows == 0) {
				continue;
			}

			histories.add(EventStatusHistory.builder()
					.event(event)
					.fromStatus(EventHistoryStatus.valueOf(fromStatus.name()))
					.toStatus(EventHistoryStatus.valueOf(toStatus.name()))
					.actorType(ActorType.SCHEDULER)
					.actorId(null)
					.reason(reason)
					.build());
		}

		eventStatusHistoryRepository.saveAll(histories);

		return histories.size();
	}
}
