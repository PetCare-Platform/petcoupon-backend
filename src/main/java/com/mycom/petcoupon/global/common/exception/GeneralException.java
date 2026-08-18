package com.mycom.petcoupon.global.common.exception;

import com.mycom.petcoupon.global.common.code.BaseErrorCode;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class GeneralException extends RuntimeException {

	private final BaseErrorCode errorCode;
}
