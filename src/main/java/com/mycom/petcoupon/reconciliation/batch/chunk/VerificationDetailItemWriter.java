package com.mycom.petcoupon.reconciliation.batch.chunk;

import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.stereotype.Component;

import com.mycom.petcoupon.reconciliation.entity.VerificationDetail;
import com.mycom.petcoupon.reconciliation.repository.VerificationDetailRepository;

import lombok.RequiredArgsConstructor;

/**
 * 청크 Step(HISTORY_MISMATCH/INVALID_STATUS/STOCK_NOT_RESTORED) 공용 Writer. 한
 * 청크(chunk-size)만큼만 저장해서 검증 결과 전체를 메모리에 들고 있지 않는다 — 300만 건
 * 규모에서도 메모리가 안정적이다.
 *
 * report 연관관계는 assignReport()(부모의 지연로딩 컬렉션에 append)를 쓰지 않는다 — 청크가
 * 누적되면서 그 컬렉션 전체를 메모리로 끌어올리게 되기 때문이다. 대신 Processor가 이미
 * VerificationDetail.builder().report(reportReference)로 FK만 채워서 넘기고, 여기서는
 * saveAll로 그대로 저장만 한다.
 */
@Component
@RequiredArgsConstructor
public class VerificationDetailItemWriter implements ItemWriter<VerificationDetail> {

    private final VerificationDetailRepository verificationDetailRepository;

    @Override
    public void write(Chunk<? extends VerificationDetail> chunk) {
        verificationDetailRepository.saveAll(chunk.getItems());
    }
}
