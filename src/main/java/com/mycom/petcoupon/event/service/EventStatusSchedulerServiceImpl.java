package com.mycom.petcoupon.event.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
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
	@Override
	@Transactional
	@Scheduled(cron = "${event.status.scheduler.cron:0 * * * * *}")
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
		transition(targets, EventStatus.OPEN, "오픈 시각 도달");
		return targets.size();
	}

	private int closeOpenEvents(LocalDateTime now) {
		List<Event> targets = eventRepository.findByStatusAndCloseAtLessThanEqual(EventStatus.OPEN, now);
		transition(targets, EventStatus.CLOSED, "종료 시각 도달");
		return targets.size();
	}

	private void transition(List<Event> events, EventStatus toStatus, String reason) {
		for (Event event : events) {
			EventStatus fromStatus = event.getStatus();
			event.updateStatus(toStatus);

			eventStatusHistoryRepository.save(EventStatusHistory.builder()
					.event(event)
					.fromStatus(EventHistoryStatus.valueOf(fromStatus.name()))
					.toStatus(EventHistoryStatus.valueOf(toStatus.name()))
					.actorType(ActorType.SCHEDULER)
					.actorId(null)
					.reason(reason)
					.build());
		}
	}
}
