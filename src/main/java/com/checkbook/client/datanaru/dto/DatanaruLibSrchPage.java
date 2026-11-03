package com.checkbook.client.datanaru.dto;

import java.util.List;

/**
 * libSrch 한 쪽. 목록 동기화가 "전부 받았는지"를 numFound로 확인하려고 쓴다.
 *
 * @param numFound      정보나루가 알려 준 전체 도서관 수. 응답 본문이 비었으면 null(전체를 모름)
 * @param receivedCount 이 쪽에 실제로 온 항목 수(좌표·코드가 빠진 항목까지 센다)
 * @param libraries     코드가 있는 항목. 좌표가 없는 도서관도 들어 있다(lat·lon이 null)
 */
public record DatanaruLibSrchPage(Integer numFound, int receivedCount, List<DatanaruLibSrchResult> libraries) {
}
