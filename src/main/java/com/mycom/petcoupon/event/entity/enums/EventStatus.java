package com.mycom.petcoupon.event.entity.enums;

public enum EventStatus {
	SCHEDULED,
	OPEN,
	CLOSED;

	public boolean canTransitionTo(EventStatus target) {
		return (this == SCHEDULED && target == OPEN) || (this == OPEN && target == CLOSED);
	}
}
