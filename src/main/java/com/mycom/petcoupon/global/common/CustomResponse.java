package com.mycom.petcoupon.global.common;

import org.springframework.http.HttpStatus;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.mycom.petcoupon.global.common.code.BaseErrorCode;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor(access = AccessLevel.PRIVATE)
@JsonPropertyOrder({"isSuccess", "code", "message", "result"})
@Getter
public class CustomResponse<T> {
	@JsonProperty("isSuccess")
    private boolean isSuccess;

    @JsonProperty("code")
    private String code;

    @JsonProperty("message")
    private String message;

    @JsonProperty("result")
    private final T result;

    // 기본 성공 응답
    public static <T> CustomResponse<T> onSuccess(T result) {
        return new CustomResponse<>(true, String.valueOf(HttpStatus.OK.value()), HttpStatus.OK.getReasonPhrase(), result);
    }

    // 지정한 HTTP 상태 코드의 성공 응답
    public static <T> CustomResponse<T> onSuccess(HttpStatus status, T result) {
        return new CustomResponse<>(true, String.valueOf(status.value()), status.getReasonPhrase(), result);
    }

    // 결과 데이터를 포함한 실패 응답
    public static <T> CustomResponse<T> onFailure(String code, String message, T result) {
        return new CustomResponse<>(false, code, message, result);
    }

    // 결과 데이터가 없는 실패 응답
    public static <T> CustomResponse<T> onFailure(String code, String message) {
        return new CustomResponse<>(false, code, message, null);
    }

    // 에러 코드 기반 실패 응답
    public static <T> CustomResponse<T> onFailure(BaseErrorCode errorCode) {
        return new CustomResponse<>(false, errorCode.getCode(), errorCode.getMessage(), null);
    }
}
