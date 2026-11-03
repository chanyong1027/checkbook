package com.checkbook.publiclibrary.repository;

import com.checkbook.common.config.JpaConfig;
import com.checkbook.publiclibrary.domain.PublicLibrary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 DB(H2)로 주변 검색이 정보나루에서 빠진(비활성) 도서관을 후보에 넣지 않는지 본다. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(JpaConfig.class)
class PublicLibraryRepositoryIT {

    @Autowired PublicLibraryRepository repository;

    @BeforeEach
    void seed() {
        repository.deleteAll();
        PublicLibrary closed = lib("CLOSED", 37.5001);
        closed.deactivate();
        repository.saveAll(List.of(lib("OPEN1", 37.5002), closed, lib("OPEN2", 37.5003)));
    }

    @Test
    void 반경_검색은_비활성_도서관을_뺀다() {
        assertThat(repository.findWithinRadius(37.5, 127.0, 10))
                .extracting(PublicLibrary::getLibCode).containsExactly("OPEN1", "OPEN2");
    }

    @Test
    void 가까운_순_검색도_비활성_도서관을_뺀다() {
        assertThat(repository.findNearest(37.5, 127.0, 3))
                .extracting(PublicLibrary::getLibCode).containsExactly("OPEN1", "OPEN2");
    }

    private static PublicLibrary lib(String code, double lat) {
        return PublicLibrary.builder().libCode(code).name(code).lat(lat).lon(127.0).build();
    }
}
