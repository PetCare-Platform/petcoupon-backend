package com.mycom.petcoupon.coupon.issue.service;

import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import com.mycom.petcoupon.coupon.exception.CouponErrorCode;
import com.mycom.petcoupon.coupon.issue.dto.CouponIssueLuaResult;
import com.mycom.petcoupon.coupon.issue.dto.CouponIssueRealtimeStock;
import com.mycom.petcoupon.coupon.issue.dto.CouponIssueStockRestoreResult;
import com.mycom.petcoupon.coupon.issue.dto.enums.CouponIssueLuaResultStatus;
import com.mycom.petcoupon.coupon.issue.dto.enums.CouponIssueStockRestoreStatus;
import com.mycom.petcoupon.global.common.exception.GeneralException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class CouponIssueLuaServiceImpl implements CouponIssueLuaService {

	private final StringRedisTemplate redisTemplate;
	
	@Qualifier("couponIssueLuaScript")
    private final DefaultRedisScript<List> couponIssueLuaScript;
	
	@Qualifier("couponIssueRestoreLuaScript")
    private final DefaultRedisScript<List> couponIssueRestoreLuaScript;
 
    private String issueKey(String suffix, Long couponId) {
        return "coupon:issue:" + suffix + ":{" + couponId + "}";
    }
    
    @Override
    public CouponIssueLuaResult issue(Long couponId, Long userId, String requestId) {
    	validate(couponId, userId, requestId);
    	
    	List<?> luaResult;
    	
    	try {
    		luaResult = redisTemplate.execute(
    	    	couponIssueLuaScript,
    	    	List.of(
    	    		issueKey("stock", couponId), 
    	    		issueKey("applicants", couponId),
    	    	    issueKey("sequence", couponId),
    	    	    issueKey("request-sequence", couponId)
    	    	),
    	    	userId.toString(),
    	    	requestId
    	    );
    	} catch (DataAccessException e) {
    		log.error(
    			"쿠폰 발급 Lua 실행 중 Redis 접근에 실패했습니다. couponId={}, userId={}, requestId={}",
    			couponId, userId, requestId, e
    		);
    		throw new GeneralException(CouponErrorCode.ISSUE_REQUEST_SAVE_FAILED);
		}

    	try {
    		LuaNumericResult parsedResult = parseLuaNumericResult(luaResult);
    		
    		long resultCode = parsedResult.code();
            long sequenceNo = parsedResult.value();
            
            CouponIssueLuaResultStatus status = CouponIssueLuaResultStatus.from(resultCode);
    	    
            Long issuedSequenceNo = null;
            
            if (status == CouponIssueLuaResultStatus.SUCCESS || status == CouponIssueLuaResultStatus.SAME_REQUEST_RETRY) {
            	
            	if(sequenceNo <= 0) {
            		throw new IllegalArgumentException("성공 또는 재시도 결과에 유효한 순번이 없습니다. sequenceNo=" + sequenceNo);
            	}
            	
            	issuedSequenceNo = sequenceNo;
            }
            
            return CouponIssueLuaResult.builder()
	            		.status(status)
	            		.sequenceNo(issuedSequenceNo)
	            		.build();
            
    	} catch (IllegalArgumentException e) {
    		log.error(
    			"알 수 없는 쿠폰 발급 Lua 결과입니다. couponId={}, userId={}, requestId={}, result={}",
    			couponId,
    			userId,
    			requestId,  
    			luaResult,
    			e
    		);
    	    throw new GeneralException(CouponErrorCode.ISSUE_REQUEST_SAVE_FAILED);
    	}
    }
    
    private void validate(Long couponId, Long userId, String requestId) {
        if (couponId == null || couponId <= 0
                || userId == null || userId <= 0
                || requestId == null || requestId.isBlank()) {

            throw new GeneralException(CouponErrorCode.INVALID_ISSUE_REQUEST);
        }
    }
    
    @Override
	public void clearIssueState(Long couponId) {
    	validateCouponId(couponId);
    	
    	try {
    		redisTemplate.delete(List.of(
    			issueKey("stock", couponId),  
    			issueKey("applicants", couponId), 
    			issueKey("sequence", couponId),
    			issueKey("request-sequence", couponId)
    		));
    		
    	} catch (DataAccessException e) {
    		log.error(
    			"쿠폰 발급 Redis 상태 초기화에 실패했습니다. couponId={}", 
    			couponId,
    			e
    		);
    		throw new GeneralException(CouponErrorCode.ISSUE_REDIS_STATE_CLEAR_FAILED);
		}
		
	}
    
    private void validateCouponId(Long couponId) {
        if (couponId == null || couponId <= 0) {
            throw new GeneralException(CouponErrorCode.INVALID_ISSUE_REQUEST);
        }
    }

    // 실시간(=Redis 기준) 잔여 재고 조회. Lua 스크립트가 관리하는 stock 키를 GET만 하는
    // 읽기 전용 조회라 Lua가 필요 없다.
    // stock 키가 아예 없으면 "품절(0)"이 아니라 "아직 초기화 안 됨"이다 — Lua도 이 둘을
    // STOCK_NOT_INITIALIZED로 따로 구분한다(coupon-issue.lua). 발급 완료 수는 여기서 세는
    // sequence 값(선착순 순번 발급기, 재고 복구를 반영하지 않음) 대신 총수량에서 잔여를 뺀
    // 값으로 호출부(CouponConverter)가 계산한다.
    @Override
    public CouponIssueRealtimeStock getRealtimeStock(Long couponId) {
        validateCouponId(couponId);

        try {
            String stockValue = redisTemplate.opsForValue().get(issueKey("stock", couponId));

            if (stockValue == null) {
                return CouponIssueRealtimeStock.builder()
                        .initialized(false)
                        .remainingStock(0)
                        .build();
            }

            return CouponIssueRealtimeStock.builder()
                    .initialized(true)
                    .remainingStock(Integer.parseInt(stockValue))
                    .build();
        } catch (DataAccessException e) {
            log.error("쿠폰 실시간 재고 조회 중 Redis 접근에 실패했습니다. couponId={}", couponId, e);
            throw new GeneralException(CouponErrorCode.REALTIME_STOCK_READ_FAILED);
        } catch (NumberFormatException e) {
            log.error("쿠폰 실시간 재고 값이 숫자 형식이 아닙니다. couponId={}", couponId, e);
            throw new GeneralException(CouponErrorCode.REALTIME_STOCK_READ_FAILED);
        }
    }

	@Override
	public CouponIssueStockRestoreResult restoreStock(Long couponId, Long userId, String requestId, Long sequenceNo) {
		
		validateRestoreRequest(couponId, userId, requestId, sequenceNo);

		List<?> luaResult;

		try {
			luaResult = redisTemplate.execute(
					couponIssueRestoreLuaScript,
					List.of(
						issueKey("stock", couponId), 
						issueKey("applicants", couponId),
						issueKey("request-sequence", couponId)
					),
					userId.toString(), 
					requestId, 
					sequenceNo.toString()
			);
			
		} catch (DataAccessException e) {
			
			log.error("쿠폰 발급 Redis 재고 복구에 실패했습니다. " + "couponId={}, userId={}, requestId={}, sequenceNo={}", couponId, userId, requestId, sequenceNo, e);
			throw new GeneralException(CouponErrorCode.ISSUE_STOCK_RESTORE_FAILED);
		}

		try {
			LuaNumericResult parsedResult = parseLuaNumericResult(luaResult);

			CouponIssueStockRestoreStatus status = CouponIssueStockRestoreStatus.from(parsedResult.code());
			
			long remainingStock = parsedResult.value();
			
			if (remainingStock < 0 || remainingStock > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Redis 재고 복구 결과가 유효한 범위를 벗어났습니다. " + "remainingStock=" + remainingStock);
            }

			return CouponIssueStockRestoreResult.builder()
					.status(status)
					.remainingStock((int) remainingStock)
					.build();

		} catch (IllegalArgumentException e) {
			
			log.error("알 수 없는 Redis 재고 복구 결과입니다. " + "couponId={}, userId={}, requestId={}, result={}", couponId, userId, requestId, luaResult, e);
			throw new GeneralException(CouponErrorCode.ISSUE_STOCK_RESTORE_FAILED);
		}
	}
	
	private void validateRestoreRequest(Long couponId, Long userId, String requestId, Long sequenceNo) {
		
		if (couponId == null || couponId <= 0 || userId == null || userId <= 0 || requestId == null
				|| requestId.isBlank() || sequenceNo == null || sequenceNo <= 0) {

			throw new GeneralException(CouponErrorCode.INVALID_STOCK_RESTORE_REQUEST);
		}
	}
	
	// Lua Script의 공통 반환 형식인 {상태 코드, 결과 값}을 검증하고 숫자로 변환한다.
	private LuaNumericResult parseLuaNumericResult(List<?> luaResult) {
		
	    if (luaResult == null || luaResult.size() != 2) {
	        throw new IllegalArgumentException("Lua 실행 결과의 형식이 올바르지 않습니다. result=" + luaResult);
	    }

	    Object codeValue = luaResult.get(0);
	    Object resultValue = luaResult.get(1);

	    if (!(codeValue instanceof Number codeNumber) || !(resultValue instanceof Number resultNumber)) {
	        throw new IllegalArgumentException("Lua 실행 결과가 숫자 형식이 아닙니다. result=" + luaResult);
	    }

	    return new LuaNumericResult(codeNumber.longValue(), resultNumber.longValue());
	}
	
	// Lua Script의 공통 반환값
	private record LuaNumericResult(long code, long value) {}
}
