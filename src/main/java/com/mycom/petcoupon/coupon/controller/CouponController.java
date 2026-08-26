package com.mycom.petcoupon.coupon.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.mycom.petcoupon.coupon.dto.req.CouponIssueCreateRequest;
import com.mycom.petcoupon.coupon.dto.res.CouponIssueCreateResponse;
import com.mycom.petcoupon.coupon.dto.res.CouponRealtimeStatusResponse;
import com.mycom.petcoupon.coupon.exception.CouponErrorCode;
import com.mycom.petcoupon.coupon.repository.CouponRepository;
import com.mycom.petcoupon.coupon.service.CouponIssueService;
import com.mycom.petcoupon.coupon.service.CouponRealtimeStatusService;
import com.mycom.petcoupon.global.common.CustomResponse;
import com.mycom.petcoupon.global.common.code.CommonErrorCode;
import com.mycom.petcoupon.global.common.exception.GeneralException;
import com.mycom.petcoupon.idempotency.service.IdempotencyDecision;
import com.mycom.petcoupon.idempotency.service.IdempotencyKeyService;
import com.mycom.petcoupon.user.repository.AppUserRepository;

import tools.jackson.databind.ObjectMapper;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/**
 * 선착순 쿠폰 신청 API 진입점.
 * Idempotency-Key 기반 API 레벨 멱등성을 여기서 앞단에 두르고, 실제 발급 로직(CouponIssueService)은
 * 그대로 둔다. 실패 응답은 최초 처리 때만 여기서 직접 안 만든다 — service가 던진 GeneralException을
 * GlobalExceptionHandler가 잡아서 CustomResponse.onFailure(...)로 변환한다.
 */
@RestController // @RequestMapping만 있으면 컨트롤러 빈으로 등록도, JSON 직렬화도 안 됨
@Validated // @PathVariable/@RequestHeader 등 메서드 파라미터에 붙은 제약을 실제로 검증하려면 필요
@RequiredArgsConstructor
public class CouponController {

    private final CouponIssueService couponIssueService;
    private final IdempotencyKeyService idempotencyKeyService;
    private final CouponRepository couponRepository;
    private final AppUserRepository appUserRepository;
    private final CouponRealtimeStatusService couponRealtimeStatusService;
    private final ObjectMapper objectMapper;

    @PostMapping("/coupons/{couponId}/issues")
    public ResponseEntity<?> issue(
            @PathVariable("couponId") @Positive Long couponId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 64) String idempotencyKey,
            @Valid @RequestBody CouponIssueCreateRequest request) {

        // 0단계: 쿠폰 존재 확인을 멱등성 체크보다 먼저 한다.
        // IdempotencyKey가 coupon_id를 FK로 물고 있어서, 이 확인 없이 바로 진행하면
        // 없는 쿠폰 요청이 "404 COUPON_NOT_FOUND"가 아니라 FK 위반(500)으로 터진다.
        if (!couponRepository.existsById(couponId)) {
            return ResponseEntity.status(CouponErrorCode.COUPON_NOT_FOUND.getStatus())
                    .body(CouponErrorCode.COUPON_NOT_FOUND.getErrorResponse());
        }

        // 0-b단계: userId 존재 확인도 같은 이유로 미리 한다. IdempotencyKey가 user_id도 FK로 물고 있어서,
        // 없는 userId로 요청하면 idempotency_key INSERT가 FK 위반(500)으로 터진다 — 그 전에 404로 막는다.
        if (!appUserRepository.existsById(request.userId())) {
            return ResponseEntity.status(CommonErrorCode.NOT_FOUND.getStatus())
                    .body(CommonErrorCode.NOT_FOUND.getErrorResponse());
        }

        // 1단계: 멱등성 판단. 이 한 번의 호출로 "새 시도/재현/처리중/키재사용" 네 갈래가 갈린다.
        IdempotencyDecision decision = idempotencyKeyService.begin(request.userId(), couponId, idempotencyKey);

        // 1-a. 아직 처리 중인 시도가 있음 — 재실행하지 않고 "기다려라"만 응답
        if (decision.type() == IdempotencyDecision.Type.CONFLICT) {
            return ResponseEntity.status(CouponErrorCode.REQUEST_IN_PROGRESS.getStatus())
                    .body(CouponErrorCode.REQUEST_IN_PROGRESS.getErrorResponse());
        }

        // 1-b. 같은 키를 다른 요청(다른 쿠폰 등)에 재사용함 — 클라이언트 오사용으로 보고 차단
        if (decision.type() == IdempotencyDecision.Type.KEY_REUSED) {
            return ResponseEntity.status(CouponErrorCode.IDEMPOTENCY_KEY_REUSED.getStatus())
                    .body(CouponErrorCode.IDEMPOTENCY_KEY_REUSED.getErrorResponse());
        }

        // 1-c. 이미 끝난 시도(성공 또는 저장된 실패) — 본처리를 다시 태우지 않고 그때 응답을 그대로 반환
        if (decision.type() == IdempotencyDecision.Type.REPLAY) {
            return ResponseEntity.status(decision.replayStatus())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(decision.replayBody());
        }

        // 2단계 (PROCEED) — 처음 보는 요청이거나 죽은 시도를 이어받은 경우만 여기까지 온다.
        // 실제 발급 로직(CouponIssueService)을 실행하고, 그 결과를 반드시 idempotency_key에 기록한다.
        // recordId를 기록해두지 않으면 이 레코드가 영원히 IN_PROGRESS로 남는다.
        Long recordId = decision.recordId();
        // Idempotency-Key는 (user_id, idempotency_key)에서만 유니크해서 서로 다른 유저가 같은 값을 보낼 수 있다.
        // requestId는 coupon_issue.request_id/issue_message.message_key처럼 전역 유니크해야 하는 곳에 쓰이므로,
        // 전역 유일한 idempotency_id(recordId) 기반으로 별도 생성한다.
        String requestId = "issue:" + recordId;
        try {
            CouponIssueCreateResponse response = couponIssueService.issue(couponId, request, requestId);
            CustomResponse<CouponIssueCreateResponse> success = CustomResponse.onSuccess(response);
            // 성공 응답을 통째로 저장 — 다음에 같은 키가 오면 이 JSON을 그대로 재현한다
            
            idempotencyKeyService.succeed(recordId, HttpStatus.OK.value(), writeJson(success));
            return ResponseEntity.ok(success);
        } catch (GeneralException ex) {
            // 재고소진/중복 등 "정상적으로 끝까지 처리된 실패" — 이 실패 응답도 성공과 동일하게 저장해서 재현한다
            
            CustomResponse<Void> failure = CustomResponse.onFailure(ex.getErrorCode());
            idempotencyKeyService.fail(recordId, ex.getErrorCode().getStatus().value(), writeJson(failure));
            throw ex; // 이번 요청 자체의 응답 형태는 GlobalExceptionHandler가 그대로 만들어줌 (여기서 새로 안 만듦)
        } catch (RuntimeException ex) {
            // Redis 등 인프라 예외 — 만들어줄 응답이 없으므로 body 없이 FAILED 처리만 하고,
            // 다음 재시도 때 begin()이 이걸 "응답 없는 FAILED"로 인식해 재처리를 허용하게 한다.
            
            idempotencyKeyService.failWithoutBody(recordId);
            throw ex;
        }
    }

    // 쿠폰 실시간 요청 현황 조회 — 잔여 재고·발급 완료 수는 Redis 기준(실시간), 총 수량은 DB 기준
    @GetMapping("/coupons/{couponId}/status")
    public CustomResponse<CouponRealtimeStatusResponse> getRealtimeStatus(
            @PathVariable("couponId") @Positive Long couponId) {
        CouponRealtimeStatusResponse response = couponRealtimeStatusService.getRealtimeStatus(couponId);
        return CustomResponse.onSuccess(response);
    }

    // 응답 객체를 JSON 문자열로 직렬화해서 idempotency_key.response_body에 저장하기 위한 헬퍼.
    // (Jackson 3라 writeValueAsString이 unchecked 예외라 별도 try-catch가 필요 없다)
    private String writeJson(Object body) {
        return objectMapper.writeValueAsString(body);
    }
}
