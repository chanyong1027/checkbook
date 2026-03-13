package com.checkbook.client;

import com.checkbook.dto.EBookSearchResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * 여러 전자도서관을 동시에 검색하는 서비스
 *
 * PoC 단계: 교보 전자도서관(dkyobobook.co.kr)만 지원
 * TODO: 북큐브, Yes24, 알라딘 클라이언트 추가
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class ELibSearchService {

    private final KyoboELibClient kyoboClient;
    @Qualifier("eLibraryExecutor")
    private final ExecutorService executor;

    /**
     * PoC용: 교보 전자도서관 여러 곳을 동시 검색
     *
     * 교보 전자도서관 도메인 패턴:
     *   - 신형: {기관}.dkyobobook.co.kr
     *   - 구형: {기관}.mhebook.co.kr 또는 자체 도메인/elibrary-front/
     *
     * 모든 교보 전자도서관은 searchList.ink 파라미터 구조가 동일하므로
     * 도메인(base URL)만 바꾸면 같은 파서로 파싱 가능.
     */
    private static final List<String> KYOBO_LIBRARIES = List.of(
            // --- PoC용 샘플 도서관 (로그인 없이 검색 가능한 것들) ---
            "https://duksunguniv.dkyobobook.co.kr",  // 덕성여대
            "https://seocholib.dkyobobook.co.kr",    // 서초구 전자도서관
            "https://semas.dkyobobook.co.kr",        // 소상공인시장진흥공단
            "https://onbook.dkyobobook.co.kr"        // 온책방
            // TODO: 로그인 필요 여부 확인 후 추가
            // "https://yonsei.dkyobobook.co.kr",    // 연세대
            // "https://cnu.dkyobobook.co.kr",       // 충남대
    );

    public List<EBookSearchResult> search(String keyword) {
        log.info("통합 검색 시작: keyword={}", keyword);
        long start = System.currentTimeMillis();

        // 모든 도서관에 동시 요청
        List<CompletableFuture<List<EBookSearchResult>>> futures = KYOBO_LIBRARIES.stream()
                .map(baseUrl -> CompletableFuture.supplyAsync(
                        () -> kyoboClient.search(baseUrl, keyword), executor))
                .toList();

        // 전체 결과 취합
        List<EBookSearchResult> allResults = new ArrayList<>();
        for (CompletableFuture<List<EBookSearchResult>> future : futures) {
            try {
                allResults.addAll(future.join());
            } catch (Exception e) {
                log.warn("도서관 검색 실패, 건너뜀", e);
            }
        }

        long elapsed = System.currentTimeMillis() - start;
        log.info("통합 검색 완료: {}건, {}ms", allResults.size(), elapsed);

        return allResults;
    }

    /**
     * 단일 도서관 검색 (디버깅/테스트용)
     */
    public List<EBookSearchResult> searchSingle(String baseUrl, String keyword) {
        return kyoboClient.search(baseUrl, keyword);
    }
}
