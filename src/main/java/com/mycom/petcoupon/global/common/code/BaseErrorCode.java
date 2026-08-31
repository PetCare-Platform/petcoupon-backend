package com.mycom.petcoupon.global.common.code;

import org.springframework.http.HttpStatus;

import com.mycom.petcoupon.global.common.CustomResponse;

public interface BaseErrorCode {
	
	HttpStatus getStatus();
	String getCode();
	String getMessage();
	
	default CustomResponse<Void> getErrorResponse() {
		return CustomResponse.onFailure(getCode(), getMessage());
	}
}
