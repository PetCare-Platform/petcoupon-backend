package com.mycom.petcoupon.global.auth.service;

/**
 * 관리자 인증 테스트가 앱을 통째로 띄울 때 꺼야 하는 배경 작업 목록.
 *
 * 인증 테스트는 Redis만 있으면 되는데 @SpringBootTest는 앱 전체를 올린다.
 * (@DataRedisTest 같은 슬라이스를 쓰면 PetCouponApplication의 @EnableJpaAuditing이
 * JPA 없이 적용돼 "JPA metamodel must not be empty"로 컨텍스트가 깨진다.)
 *
 * 그대로 두면 이 컨텍스트의 스케줄러와 Kafka 컨슈머가 살아 움직이면서,
 * 같은 MySQL·Redis·Kafka를 공유하는 다른 테스트의 데이터를 건드린다.
 * 실제로 이벤트 상태 스케줄러가 event_status_history를 써서 다른 테스트의
 * event 삭제가 외래키 제약에 걸린 적이 있다.
 *
 * 주기를 늘리는 방식은 쓰지 않는다. cron은 절대 시각 기준이라 "1시간 주기"로 두면
 * 테스트가 정각을 걸치는 순간에만 실행돼, 재현되지 않는 실패가 생긴다.
 * 실행 여부를 끄는 게 확실하다.
 */
final class AdminAuthTestProperties {

	static final String STREAM_OFF = "coupon.issue.stream.enabled=false";

	static final String OUTBOX_OFF = "coupon.issue.outbox.enabled=false";

	static final String KAFKA_LISTENER_OFF = "spring.kafka.listener.auto-startup=false";

	static final String EVENT_SCHEDULER_OFF = "event.status.scheduler.enabled=false";

	static final String COUPON_SCHEDULER_OFF = "coupon.status.enabled=false";

	private AdminAuthTestProperties() {
	}
}
