package com.checkbook.search.service;

import com.checkbook.search.dto.SearchResponse;
import com.checkbook.search.dto.SearchResponse.FailureDetail;
import com.checkbook.search.dto.SearchResponse.SectionStatusDetail;
import com.checkbook.search.dto.SearchSection;
import com.checkbook.search.dto.SearchSectionReason;
import com.checkbook.search.dto.SearchSectionStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 계약 2절 '상태와 failures의 일관성 규칙'을 모든 응답에 검사하는 공용 assertion.
 * <ul>
 *   <li>네 섹션이 모두 정확히 한 번 나온다</li>
 *   <li>FAILED 섹션에는 같은 섹션의 failures 항목이 정확히 하나</li>
 *   <li>SUCCESS·SKIPPED 섹션은 failures에 없다</li>
 *   <li>FAILED·SKIPPED에는 reason이 있다. SUCCESS의 reason은 null·NOT_FOUND·PRICE_UNAVAILABLE만</li>
 * </ul>
 */
final class SearchResponseConsistency {

    private SearchResponseConsistency() {
    }

    static SearchResponse assertConsistent(SearchResponse response) {
        List<SectionStatusDetail> statuses = response.metadata().sectionStatuses();
        List<FailureDetail> failures = response.metadata().failures();

        assertThat(statuses).extracting(SectionStatusDetail::section)
                .as("네 섹션이 정확히 한 번씩")
                .containsExactlyInAnyOrder(SearchSection.values());

        for (SectionStatusDetail status : statuses) {
            long failureCount = failures.stream().filter(f -> f.section() == status.section()).count();
            if (status.status() == SearchSectionStatus.FAILED) {
                assertThat(failureCount).as("%s FAILED → failures 정확히 1개", status.section()).isEqualTo(1);
                assertThat(status.reason()).as("%s FAILED → reason 필수", status.section()).isNotNull();
            } else {
                assertThat(failureCount).as("%s %s → failures 없음", status.section(), status.status()).isZero();
            }
            if (status.status() == SearchSectionStatus.SKIPPED) {
                assertThat(status.reason()).as("%s SKIPPED → reason 필수", status.section()).isNotNull();
            }
            if (status.status() == SearchSectionStatus.SUCCESS) {
                assertThat(status.reason()).as("%s SUCCESS → reason은 없음·NOT_FOUND·PRICE_UNAVAILABLE만", status.section())
                        .isIn(null, SearchSectionReason.NOT_FOUND, SearchSectionReason.PRICE_UNAVAILABLE);
            }
        }
        assertThat(failures).extracting(FailureDetail::section)
                .as("failures는 FAILED 섹션만")
                .allMatch(section -> statuses.stream()
                        .anyMatch(s -> s.section() == section && s.status() == SearchSectionStatus.FAILED));
        return response;
    }
}
