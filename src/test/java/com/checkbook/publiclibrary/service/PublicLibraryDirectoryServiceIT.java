package com.checkbook.publiclibrary.service;

import com.checkbook.common.config.JpaConfig;
import com.checkbook.publiclibrary.domain.PublicLibrary;
import com.checkbook.publiclibrary.dto.PublicLibraryDirectoryResponse;
import com.checkbook.publiclibrary.repository.PublicLibraryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 DB(H2)로 기관 검색의 조건·정렬·페이지·와일드카드 처리를 확인한다. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({PublicLibraryDirectoryService.class, JpaConfig.class}) // JpaConfig: created_at 감사(슬라이스엔 안 올라옴)
class PublicLibraryDirectoryServiceIT {

    @Autowired PublicLibraryRepository repository;
    @Autowired PublicLibraryDirectoryService service;

    private static PublicLibrary lib(String code, String name, String address) {
        return PublicLibrary.builder().libCode(code).name(name).address(address).lat(37.5).lon(127.0).build();
    }

    @BeforeEach
    void seed() {
        repository.deleteAll();
        repository.saveAll(List.of(
                lib("C3", "용산도서관", "서울 용산구 두텁바위로"),
                lib("C1", "남산도서관", "서울 용산구 소월로"),
                lib("C2", "남산도서관", "서울 용산구 소월로 별관"),
                lib("C4", "마포중앙도서관", "서울 마포구"),
                lib("C5", "100%_도서관", "부산")));
    }

    @Test
    void 이름이나_주소에_포함되면_찾고_이름_다음_코드순으로_정렬한다() {
        PublicLibraryDirectoryResponse response = service.search(" 용산 ", 0, 20);

        assertThat(response.items()).extracting(PublicLibraryDirectoryResponse.Item::libCode)
                .containsExactly("C1", "C2", "C3");
        assertThat(response.hasNext()).isFalse();
    }

    @Test
    void 정보나루에서_빠진_비활성_도서관은_찾지_않는다() {
        PublicLibrary closed = lib("C9", "용산폐관도서관", "서울 용산구");
        closed.deactivate();
        repository.save(closed);

        assertThat(service.search("용산", 0, 20).items())
                .extracting(PublicLibraryDirectoryResponse.Item::libCode).containsExactly("C1", "C2", "C3");
    }

    @Test
    void 다음_페이지가_있으면_hasNext() {
        PublicLibraryDirectoryResponse first = service.search("용산", 0, 2);
        PublicLibraryDirectoryResponse second = service.search("용산", 1, 2);

        assertThat(first.items()).extracting(PublicLibraryDirectoryResponse.Item::libCode).containsExactly("C1", "C2");
        assertThat(first.hasNext()).isTrue();
        assertThat(second.items()).extracting(PublicLibraryDirectoryResponse.Item::libCode).containsExactly("C3");
        assertThat(second.hasNext()).isFalse();
    }

    @Test
    void 퍼센트와_밑줄은_와일드카드가_아니라_글자로_찾는다() {
        assertThat(service.search("%", 0, 20).items())
                .extracting(PublicLibraryDirectoryResponse.Item::libCode).containsExactly("C5");
        assertThat(service.search("_", 0, 20).items())
                .extracting(PublicLibraryDirectoryResponse.Item::libCode).containsExactly("C5");
    }
}
