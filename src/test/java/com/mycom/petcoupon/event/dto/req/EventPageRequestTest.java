package com.mycom.petcoupon.event.dto.req;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.mycom.petcoupon.event.exception.EventErrorCode;
import com.mycom.petcoupon.global.common.exception.GeneralException;

class EventPageRequestTest {

	@ParameterizedTest
	@ValueSource(ints = {10, 20, 50, 100})
	void acceptsSupportedPageSizes(int size) {
		EventPageRequest request = new EventPageRequest(0, size);

		assertEquals(0, request.page());
		assertEquals(size, request.size());
	}

	@Test
	void parsesNumericPageParameters() {
		EventPageRequest request = EventPageRequest.from("3", "50");

		assertEquals(3, request.page());
		assertEquals(50, request.size());
	}

	@ParameterizedTest
	@ValueSource(ints = {1, 9, 11, 30, 101})
	void rejectsUnsupportedPageSizes(int size) {
		assertInvalidPageRequest(() -> new EventPageRequest(0, size));
	}

	@Test
	void rejectsNegativePage() {
		assertInvalidPageRequest(() -> new EventPageRequest(-1, 20));
	}

	@ParameterizedTest
	@ValueSource(strings = {"page", "1.5", ""})
	void rejectsNonNumericPage(String page) {
		assertInvalidPageRequest(() -> EventPageRequest.from(page, "20"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"size", "20.0", ""})
	void rejectsNonNumericSize(String size) {
		assertInvalidPageRequest(() -> EventPageRequest.from("0", size));
	}

	private void assertInvalidPageRequest(Runnable action) {
		GeneralException exception = assertThrows(GeneralException.class, action::run);

		assertSame(EventErrorCode.INVALID_EVENT_PAGE_REQUEST, exception.getErrorCode());
	}
}
