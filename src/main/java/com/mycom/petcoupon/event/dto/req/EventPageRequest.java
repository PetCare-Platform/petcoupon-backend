package com.mycom.petcoupon.event.dto.req;

import java.util.Set;

import com.mycom.petcoupon.event.exception.EventErrorCode;
import com.mycom.petcoupon.global.common.exception.GeneralException;

public record EventPageRequest(
		int page,
		int size
) {
	public static final String DEFAULT_PAGE = "0";
	public static final String DEFAULT_SIZE = "20";

	private static final Set<Integer> ALLOWED_SIZES = Set.of(10, 20, 50, 100);

	public EventPageRequest {
		if (page < 0 || !ALLOWED_SIZES.contains(size)) {
			throw new GeneralException(EventErrorCode.INVALID_EVENT_PAGE_REQUEST);
		}
	}

	public static EventPageRequest from(String page, String size) {
		try {
			return new EventPageRequest(Integer.parseInt(page), Integer.parseInt(size));
		} catch (NumberFormatException e) {
			throw new GeneralException(EventErrorCode.INVALID_EVENT_PAGE_REQUEST);
		}
	}
}
