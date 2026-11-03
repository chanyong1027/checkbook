package com.checkbook.publiclibrary.service;

import com.checkbook.client.datanaru.DatanaruClient;
import com.checkbook.client.datanaru.dto.DatanaruLibSrchPage;
import com.checkbook.client.datanaru.dto.DatanaruLibSrchResult;
import com.checkbook.common.config.JpaConfig;
import com.checkbook.publiclibrary.domain.PublicLibrary;
import com.checkbook.publiclibrary.repository.PublicLibraryRepository;
import com.checkbook.publiclibrary.service.PublicLibraryListSynchronizer.Result;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 실제 DB(H2)와 실제 트랜잭션으로 동기화가 바꾼 값이 커밋되는지 본다(관리 엔티티의 변경 감지, 새 행 INSERT, 열 길이).
 * 시험 메서드 트랜잭션을 끄고(NOT_SUPPORTED) 동기화가 스스로 연 트랜잭션이 끝난 뒤 다시 읽는다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(JpaConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PublicLibraryListSynchronizerIT {

    @Autowired PublicLibraryRepository repository;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void 추가_갱신_비활성이_커밋된다() {
        repository.deleteAll();
        repository.saveAll(List.of(lib("KEEP", "옛이름"), lib("GONE", "빠질도서관"),
                lib("A1", "a1"), lib("A2", "a2"), lib("A3", "a3"), lib("A4", "a4"),
                lib("A5", "a5"), lib("A6", "a6"), lib("A7", "a7"), lib("A8", "a8")));
        List<DatanaruLibSrchResult> fetched = new java.util.ArrayList<>(List.of(
                result("KEEP", "새이름", "가".repeat(1500)), result("NEW", "새도서관", null)));
        for (int i = 1; i <= 8; i++) {
            fetched.add(result("A" + i, "a" + i, null));
        }
        DatanaruClient client = mock(DatanaruClient.class);
        when(client.libSrchPage(1, 100)).thenReturn(new DatanaruLibSrchPage(fetched.size(), fetched.size(), fetched));
        PublicLibraryListSynchronizer synchronizer = new PublicLibraryListSynchronizer(client, repository,
                new TransactionTemplate(transactionManager), new SimpleMeterRegistry(), 0.10);

        assertThat(synchronizer.sync()).isEqualTo(Result.SUCCESS);

        Map<String, PublicLibrary> after = repository.findAll().stream()
                .collect(Collectors.toMap(PublicLibrary::getLibCode, Function.identity()));
        assertThat(after.get("KEEP").getName()).isEqualTo("새이름");
        assertThat(after.get("KEEP").getOperatingHours()).hasSize(1000);
        assertThat(after.get("GONE").isActive()).isFalse();
        assertThat(after.get("NEW").isActive()).isTrue();
        assertThat(after).hasSize(11);
    }

    private static PublicLibrary lib(String code, String name) {
        return PublicLibrary.builder().libCode(code).name(name).address("주소").lat(37.5).lon(127.0).build();
    }

    private static DatanaruLibSrchResult result(String code, String name, String operatingHours) {
        return new DatanaruLibSrchResult(code, name, "주소", 37.5, 127.0, "11", null, null, null, operatingHours, null);
    }
}
