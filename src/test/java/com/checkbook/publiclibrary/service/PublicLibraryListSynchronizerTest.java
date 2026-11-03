package com.checkbook.publiclibrary.service;

import com.checkbook.client.datanaru.DatanaruClient;
import com.checkbook.client.datanaru.DatanaruResponseException;
import com.checkbook.client.datanaru.dto.DatanaruLibSrchPage;
import com.checkbook.client.datanaru.dto.DatanaruLibSrchResult;
import com.checkbook.publiclibrary.domain.PublicLibrary;
import com.checkbook.publiclibrary.repository.PublicLibraryRepository;
import com.checkbook.publiclibrary.service.PublicLibraryListSynchronizer.Result;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PublicLibraryListSynchronizerTest {

    private static final int PAGE_SIZE = 3;

    private final DatanaruClient client = mock(DatanaruClient.class);
    private final PublicLibraryRepository repository = mock(PublicLibraryRepository.class);
    private final TransactionTemplate transactionTemplate = new TransactionTemplate() {
        @Override
        public <T> T execute(TransactionCallback<T> action) {
            return action.doInTransaction(mock(TransactionStatus.class));
        }
    };
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final PublicLibraryListSynchronizer synchronizer =
            new PublicLibraryListSynchronizer(client, repository, transactionTemplate, meterRegistry, 0.10, PAGE_SIZE);

    @Test
    void 새_도서관은_넣고_빠진_도서관은_비활성으로_돌리고_있던_도서관은_정보를_고친다() {
        List<PublicLibrary> existing = existing(10);
        when(repository.findAll()).thenReturn(existing);
        // L000은 빠지고, L001은 이름이 바뀌고, NEW는 새로 들어온다
        List<DatanaruLibSrchResult> fetched = new ArrayList<>();
        fetched.add(result("L001", "새이름도서관", 37.6, 127.1));
        IntStream.range(2, 10).forEach(i -> fetched.add(result(code(i), "도서관" + i, 37.5, 127.0)));
        fetched.add(result("NEW", "새도서관", 37.7, 127.2));
        stubPages(fetched);

        Result result = synchronizer.sync();

        assertThat(result).isEqualTo(Result.SUCCESS);
        assertThat(existing.get(0).isActive()).isFalse();
        assertThat(existing.get(1).getName()).isEqualTo("새이름도서관");
        assertThat(existing.get(1).getLat()).isEqualTo(37.6);
        assertThat(existing.subList(1, 10)).allSatisfy(library -> assertThat(library.isActive()).isTrue());
        assertThat(savedNew()).extracting(PublicLibrary::getLibCode).containsExactly("NEW");
        assertThat(meterRegistry.get("public.library.sync").tag("result", "success").counter().count()).isEqualTo(1);
    }

    @Test
    void 다시_참여한_도서관은_되살린다() {
        List<PublicLibrary> existing = existing(10);
        existing.get(0).deactivate();
        when(repository.findAll()).thenReturn(existing);
        stubPages(IntStream.range(0, 10).mapToObj(i -> result(code(i), "도서관" + i, 37.5, 127.0)).toList());

        assertThat(synchronizer.sync()).isEqualTo(Result.SUCCESS);

        assertThat(existing.get(0).isActive()).isTrue();
    }

    @Test
    void 좌표_없는_도서관은_빠진_것으로_치지_않고_기존_좌표를_지키며_새로_넣지도_않는다() {
        List<PublicLibrary> existing = existing(10);
        when(repository.findAll()).thenReturn(existing);
        List<DatanaruLibSrchResult> fetched = new ArrayList<>();
        fetched.add(result("L000", "도서관0", null, null));
        IntStream.range(1, 10).forEach(i -> fetched.add(result(code(i), "도서관" + i, 37.5, 127.0)));
        fetched.add(result("NOCOORD", "좌표없는새도서관", null, 127.0));
        stubPages(fetched);

        assertThat(synchronizer.sync()).isEqualTo(Result.SUCCESS);

        assertThat(existing.get(0).isActive()).isTrue();
        assertThat(existing.get(0).getLat()).isEqualTo(37.5);
        assertThat(savedNew()).isEmpty();
    }

    @Test
    void 빠질_도서관이_활성의_10퍼센트를_넘으면_아무것도_바꾸지_않는다() {
        List<PublicLibrary> existing = existing(10);
        when(repository.findAll()).thenReturn(existing);
        // 10곳 중 2곳(20%)이 빠진 목록
        stubPages(IntStream.range(2, 10).mapToObj(i -> result(code(i), "도서관" + i, 37.5, 127.0)).toList());

        assertThat(synchronizer.sync()).isEqualTo(Result.TOO_MANY_REMOVED);

        assertThat(existing).allSatisfy(library -> assertThat(library.isActive()).isTrue());
        verify(repository, never()).saveAll(anyList());
        assertThat(meterRegistry.get("public.library.sync").tag("result", "too_many_removed").counter().count())
                .isEqualTo(1);
    }

    @Test
    void 정확히_10퍼센트까지는_반영한다() {
        List<PublicLibrary> existing = existing(10);
        when(repository.findAll()).thenReturn(existing);
        stubPages(IntStream.range(1, 10).mapToObj(i -> result(code(i), "도서관" + i, 37.5, 127.0)).toList());

        assertThat(synchronizer.sync()).isEqualTo(Result.SUCCESS);

        assertThat(existing.get(0).isActive()).isFalse();
    }

    @Test
    void 받은_건수가_전체_건수와_다르면_일부만_받은_것으로_보고_아무것도_바꾸지_않는다() {
        List<PublicLibrary> existing = existing(10);
        when(repository.findAll()).thenReturn(existing);
        // 전체 10건이라면서 첫 쪽(3건) 뒤에 빈 쪽이 온다
        when(client.libSrchPage(1, PAGE_SIZE)).thenReturn(page(10,
                IntStream.range(0, 3).mapToObj(i -> result(code(i), "도서관" + i, 37.5, 127.0)).toList()));
        when(client.libSrchPage(2, PAGE_SIZE)).thenReturn(page(10, List.of()));

        assertThat(synchronizer.sync()).isEqualTo(Result.INCOMPLETE);

        assertThat(existing).allSatisfy(library -> assertThat(library.isActive()).isTrue());
        verify(repository, never()).findAll();
        verify(repository, never()).saveAll(anyList());
    }

    @Test
    void 건수는_맞아도_중복_코드가_섞여_고유_코드가_모자라면_아무것도_바꾸지_않는다() {
        List<PublicLibrary> existing = existing(10);
        when(repository.findAll()).thenReturn(existing);
        // 쪽 정렬이 흔들려 L002가 두 번 오고 L009가 빠진 10건
        List<DatanaruLibSrchResult> fetched = new ArrayList<>(
                IntStream.range(0, 9).mapToObj(i -> result(code(i), "도서관" + i, 37.5, 127.0)).toList());
        fetched.add(result("L002", "도서관2", 37.5, 127.0));
        stubPages(fetched);

        assertThat(synchronizer.sync()).isEqualTo(Result.INCOMPLETE);

        assertThat(existing).allSatisfy(library -> assertThat(library.isActive()).isTrue());
        verify(repository, never()).saveAll(anyList());
    }

    @Test
    void 열_길이를_넘는_값은_잘라_넣고_주소가_비어_오면_기존_주소를_지킨다() {
        List<PublicLibrary> existing = existing(10);
        when(repository.findAll()).thenReturn(existing);
        List<DatanaruLibSrchResult> fetched = new ArrayList<>();
        fetched.add(new DatanaruLibSrchResult("L000", "도서관0", null, 37.5, 127.0, "11", null, null, null,
                "가".repeat(1500), null));
        IntStream.range(1, 10).forEach(i -> fetched.add(result(code(i), "도서관" + i, 37.5, 127.0)));
        fetched.add(result("NEW", "나".repeat(300), 37.7, 127.2));
        stubPages(fetched);

        assertThat(synchronizer.sync()).isEqualTo(Result.SUCCESS);

        assertThat(existing.get(0).getOperatingHours()).hasSize(1000);
        assertThat(existing.get(0).getAddress()).isEqualTo("옛주소");
        assertThat(savedNew()).singleElement().satisfies(added -> assertThat(added.getName()).hasSize(200));
    }

    @Test
    void 받는_도중_전체_건수가_바뀌면_쪽_경계가_밀렸을_수_있어_아무것도_바꾸지_않는다() {
        List<PublicLibrary> existing = existing(6);
        when(repository.findAll()).thenReturn(existing);
        // 1쪽은 전체 6건이라 했는데, 2쪽을 받을 때는 목록이 바뀌어 7건이 됐다(앞에서 1곳 빠지고 끝에 2곳 추가)
        when(client.libSrchPage(1, PAGE_SIZE)).thenReturn(page(6,
                IntStream.range(0, 3).mapToObj(i -> result(code(i), "도서관" + i, 37.5, 127.0)).toList()));
        when(client.libSrchPage(2, PAGE_SIZE)).thenReturn(page(7,
                IntStream.range(4, 7).mapToObj(i -> result(code(i), "도서관" + i, 37.5, 127.0)).toList()));

        assertThat(synchronizer.sync()).isEqualTo(Result.INCOMPLETE);

        assertThat(existing).allSatisfy(library -> assertThat(library.isActive()).isTrue());
        verify(repository, never()).saveAll(anyList());
    }

    @Test
    void 전체_건수를_모르면_아무것도_바꾸지_않는다() {
        when(client.libSrchPage(1, PAGE_SIZE)).thenReturn(new DatanaruLibSrchPage(null, 0, List.of()));

        assertThat(synchronizer.sync()).isEqualTo(Result.INCOMPLETE);

        verify(repository, never()).saveAll(anyList());
    }

    @Test
    void 정보나루_호출이_실패하면_아무것도_바꾸지_않는다() {
        when(client.libSrchPage(anyInt(), anyInt())).thenThrow(new DatanaruResponseException("정보나루 libSrch 실패"));

        assertThat(synchronizer.sync()).isEqualTo(Result.FAILED);

        verify(repository, never()).findAll();
        verify(repository, never()).saveAll(anyList());
        assertThat(meterRegistry.get("public.library.sync").tag("result", "failed").counter().count()).isEqualTo(1);
    }

    @Test
    void 결과_카운터는_실행_전에도_모든_결과가_0으로_있다() {
        for (Result result : Result.values()) {
            assertThat(meterRegistry.get("public.library.sync").tag("result", result.tag()).counter().count()).isZero();
        }
    }

    // --- helpers ---

    private void stubPages(List<DatanaruLibSrchResult> all) {
        int pageNo = 1;
        for (int from = 0; from < all.size(); from += PAGE_SIZE) {
            List<DatanaruLibSrchResult> slice = all.subList(from, Math.min(from + PAGE_SIZE, all.size()));
            when(client.libSrchPage(pageNo++, PAGE_SIZE)).thenReturn(page(all.size(), slice));
        }
        when(client.libSrchPage(pageNo, PAGE_SIZE)).thenReturn(page(all.size(), List.of()));
    }

    private static DatanaruLibSrchPage page(int numFound, List<DatanaruLibSrchResult> libraries) {
        return new DatanaruLibSrchPage(numFound, libraries.size(), libraries);
    }

    @SuppressWarnings("unchecked")
    private List<PublicLibrary> savedNew() {
        ArgumentCaptor<List<PublicLibrary>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        return captor.getValue();
    }

    private static List<PublicLibrary> existing(int count) {
        return new ArrayList<>(IntStream.range(0, count)
                .mapToObj(i -> PublicLibrary.builder().libCode(code(i)).name("도서관" + i).address("옛주소")
                        .lat(37.5).lon(127.0).build())
                .toList());
    }

    private static String code(int i) {
        return String.format("L%03d", i);
    }

    private static DatanaruLibSrchResult result(String libCode, String name, Double lat, Double lon) {
        return new DatanaruLibSrchResult(libCode, name, "주소", lat, lon, "11", null, null, null, null, null);
    }
}
