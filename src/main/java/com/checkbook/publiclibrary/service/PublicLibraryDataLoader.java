package com.checkbook.publiclibrary.service;

import com.checkbook.client.datanaru.DatanaruClient;
import com.checkbook.client.datanaru.dto.DatanaruLibSrchResult;
import com.checkbook.publiclibrary.domain.PublicLibrary;
import com.checkbook.publiclibrary.repository.PublicLibraryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "public-library.bootstrap-enabled", havingValue = "true")
public class PublicLibraryDataLoader implements ApplicationRunner {

    private final DatanaruClient datanaruClient;
    private final PublicLibraryRepository repository;
    private final TransactionTemplate transactionTemplate;

    @Override
    public void run(ApplicationArguments args) {
        if (repository.count() > 0) {
            log.info("공공도서관 데이터 이미 적재됨 ({}건) - 스킵", repository.count());
            return;
        }

        transactionTemplate.executeWithoutResult(status -> {
            log.info("공공도서관 초기 적재 시작 (정보나루 libSrch API)");
            int pageNo = 1;
            int pageSize = 100;
            int total = 0;

            while (true) {
                List<DatanaruLibSrchResult> page = datanaruClient.libSrch(pageNo, pageSize);
                if (page.isEmpty()) {
                    break;
                }

                List<PublicLibrary> entities = page.stream()
                        .filter(result -> result.libCode() != null
                                && result.name() != null
                                && result.lat() != null
                                && result.lon() != null)
                        .map(result -> PublicLibrary.builder()
                                .libCode(result.libCode())
                                .name(result.name())
                                .address(result.address())
                                .lat(result.lat())
                                .lon(result.lon())
                                .regionName(result.regionName())
                                .homepage(result.homepage())
                                .phone(result.phone())
                                .fax(result.fax())
                                .operatingHours(result.operatingHours())
                                .closedDays(result.closedDays())
                                .build())
                        .toList();

                repository.saveAll(entities);
                total += entities.size();

                if (page.size() < pageSize) {
                    break;
                }
                pageNo++;
            }

            log.info("공공도서관 초기 적재 완료: {}건", total);
        });
    }
}
