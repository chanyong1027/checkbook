package com.checkbook.publiclibrary.service;

import com.checkbook.common.exception.BusinessException;
import com.checkbook.publiclibrary.domain.PublicLibrary;
import com.checkbook.publiclibrary.dto.PublicLibraryAvailabilityPage;
import com.checkbook.publiclibrary.repository.PublicLibraryRepository;
import com.checkbook.publiclibrary.snapshot.domain.SnapshotSourceStatus;
import com.checkbook.publiclibrary.snapshot.dto.LibraryAvailabilityResult;
import com.checkbook.publiclibrary.snapshot.service.LibraryAvailabilitySnapshotService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PublicLibraryAvailabilityServiceTest {

    @Mock
    private LibraryAvailabilitySnapshotService snapshotService;
    @Mock
    private PublicLibraryRepository publicLibraryRepository;

    private ExecutorService publicLibraryExecutor;
    private PublicLibraryAvailabilityService service;

    @BeforeEach
    void setUp() {
        publicLibraryExecutor = Executors.newFixedThreadPool(20);
        service = new PublicLibraryAvailabilityService(
                snapshotService, publicLibraryRepository, publicLibraryExecutor);
        ReflectionTestUtils.setField(service, "pageSize", 5);
        ReflectionTestUtils.setField(service, "maxCount", 20);
        ReflectionTestUtils.setField(service, "fanoutTimeoutMs", 2200L);
    }

    @AfterEach
    void tearDown() {
        publicLibraryExecutor.shutdownNow();
    }

    private PublicLibrary lib(int i) {
        return PublicLibrary.builder()
                .libCode(String.format("L%03d", i))
                .name("도서관" + i)
                .address("서울 중구 " + i)
                .lat(37.5665 + i * 0.001)
                .lon(126.9780 + i * 0.001)
                .homepage("https://lib" + i + ".example")
                .build();
    }

    private List<PublicLibrary> libs(int count) {
        List<PublicLibrary> list = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            list.add(lib(i));
        }
        return list;
    }

    private void stubAllSuccess() {
        when(snapshotService.getAvailability(eq("9788936439743"), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(inv -> new LibraryAvailabilityResult(
                        inv.getArgument(1), true, false, SnapshotSourceStatus.SUCCESS));
    }

    @Test
    void firstPageReturnsFiveWithNextOffset() {
        when(publicLibraryRepository.findNearest(37.5665, 126.9780, 20)).thenReturn(libs(20));
        stubAllSuccess();

        PublicLibraryAvailabilityPage page = service.fetch("9788936439743", 37.5665, 126.9780, 0);

        assertThat(page.libraries()).hasSize(5);
        assertThat(page.total()).isEqualTo(20);
        assertThat(page.offset()).isZero();
        assertThat(page.hasMoreLibraries()).isTrue();
        assertThat(page.nextOffset()).isEqualTo(5);
        assertThat(page.failedCount()).isZero();
    }

    @Test
    void lastPageHasNullNextOffset() {
        when(publicLibraryRepository.findNearest(37.5665, 126.9780, 20)).thenReturn(libs(20));
        stubAllSuccess();

        PublicLibraryAvailabilityPage page = service.fetch("9788936439743", 37.5665, 126.9780, 15);

        assertThat(page.libraries()).hasSize(5);
        assertThat(page.hasMoreLibraries()).isFalse();
        assertThat(page.nextOffset()).isNull();
    }

    @Test
    void offsetAtBoundaryReturnsEmptyPage() {
        when(publicLibraryRepository.findNearest(37.5665, 126.9780, 20)).thenReturn(libs(20));

        PublicLibraryAvailabilityPage page = service.fetch("9788936439743", 37.5665, 126.9780, 20);

        assertThat(page.libraries()).isEmpty();
        assertThat(page.total()).isEqualTo(20);
        assertThat(page.hasMoreLibraries()).isFalse();
        assertThat(page.nextOffset()).isNull();
        assertThat(page.failedCount()).isZero();
    }

    @Test
    void offsetBeyondCandidatesReturnsEmptyPageNotException() {
        when(publicLibraryRepository.findNearest(37.5665, 126.9780, 20)).thenReturn(libs(20));

        PublicLibraryAvailabilityPage page = service.fetch("9788936439743", 37.5665, 126.9780, 25);

        assertThat(page.libraries()).isEmpty();
        assertThat(page.hasMoreLibraries()).isFalse();
        assertThat(page.nextOffset()).isNull();
    }

    @Test
    void sparseRegionOffsetBeyondReturnsEmpty() {
        when(publicLibraryRepository.findNearest(37.5665, 126.9780, 20)).thenReturn(libs(3));

        PublicLibraryAvailabilityPage page = service.fetch("9788936439743", 37.5665, 126.9780, 20);

        assertThat(page.libraries()).isEmpty();
        assertThat(page.total()).isEqualTo(3);
        assertThat(page.hasMoreLibraries()).isFalse();
    }

    @Test
    void allTargetsFailYieldsEmptyLibrariesWithFailedCount() {
        when(publicLibraryRepository.findNearest(37.5665, 126.9780, 20)).thenReturn(libs(20));
        // 5개 targets 전부 sourceStatus=FAILED → 드롭
        when(snapshotService.getAvailability(eq("9788936439743"), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(inv -> LibraryAvailabilityResult.failed(inv.getArgument(1)));

        PublicLibraryAvailabilityPage page = service.fetch("9788936439743", 37.5665, 126.9780, 0);

        assertThat(page.libraries()).isEmpty();
        assertThat(page.failedCount()).isEqualTo(5);
        // 후보 소진과 구분: hasMore는 후보 기준으로 여전히 true
        assertThat(page.hasMoreLibraries()).isTrue();
        assertThat(page.nextOffset()).isEqualTo(5);
    }

    @Test
    void partialFailureKeepsPagingAnchorsByCandidate() {
        when(publicLibraryRepository.findNearest(37.5665, 126.9780, 20)).thenReturn(libs(20));
        // L001만 실패, 나머지 성공 → libraries 4개 + failedCount 1, 앵커는 후보 기준
        when(snapshotService.getAvailability(eq("9788936439743"), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(inv -> {
                    String code = inv.getArgument(1);
                    if ("L001".equals(code)) {
                        return LibraryAvailabilityResult.failed(code);
                    }
                    return new LibraryAvailabilityResult(code, true, false, SnapshotSourceStatus.SUCCESS);
                });

        PublicLibraryAvailabilityPage page = service.fetch("9788936439743", 37.5665, 126.9780, 0);

        assertThat(page.libraries()).hasSize(4);
        assertThat(page.failedCount()).isEqualTo(1);
        assertThat(page.total()).isEqualTo(20);
        assertThat(page.nextOffset()).isEqualTo(5);
    }

    @Test
    void hugeOffsetDoesNotOverflowReturnsEmptyPage() {
        when(publicLibraryRepository.findNearest(37.5665, 126.9780, 20)).thenReturn(libs(20));

        PublicLibraryAvailabilityPage page = service.fetch(
                "9788936439743", 37.5665, 126.9780, Integer.MAX_VALUE);

        assertThat(page.libraries()).isEmpty();
        assertThat(page.total()).isEqualTo(20);
        assertThat(page.hasMoreLibraries()).isFalse();
        assertThat(page.nextOffset()).isNull();
        assertThat(page.failedCount()).isZero();
    }

    @Test
    void latOutOfRangeThrowsInvalidLocation() {
        assertThatThrownBy(() -> service.fetch("9788936439743", 999.0, 126.9780, 0))
                .isInstanceOf(BusinessException.class);
    }
}
