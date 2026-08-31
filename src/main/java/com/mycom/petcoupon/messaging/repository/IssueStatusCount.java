package com.mycom.petcoupon.messaging.repository;

import com.mycom.petcoupon.messaging.entity.enums.IssueMessageStatus;

// 상태 분포 조회(#156)용 인터페이스 프로젝션.
public interface IssueStatusCount {

    IssueMessageStatus getStatus();

    Long getCount();
}
