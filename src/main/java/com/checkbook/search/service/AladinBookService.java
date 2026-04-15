package com.checkbook.search.service;

import com.checkbook.aladinstore.repository.AladinStoreRepository;
import com.checkbook.client.aladin.AladinClient;
import com.checkbook.client.aladin.dto.AladinSearchResult;
import com.checkbook.client.aladin.dto.AladinUsedBookResult;
import com.checkbook.common.util.InputNormalizer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AladinBookService {

    private final AladinClient aladinClient;
    private final AladinStoreRepository aladinStoreRepository;

    public Optional<AladinSearchResult> identify(InputNormalizer.NormalizedQuery normalized) {
        if (normalized.type() == InputNormalizer.QueryType.ISBN) {
            return aladinClient.lookupBook(normalized.value());
        }
        return aladinClient.searchBook(normalized.value());
    }

    public AladinUsedBookResult getUsedBooks(String isbn13) {
        return aladinClient.getUsedBooks(isbn13);
    }
}
