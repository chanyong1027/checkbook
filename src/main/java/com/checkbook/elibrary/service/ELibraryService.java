package com.checkbook.elibrary.service;

import com.checkbook.common.exception.BusinessException;
import com.checkbook.common.exception.ErrorCode;
import com.checkbook.elibrary.domain.ELibraryStatus;
import com.checkbook.elibrary.domain.VendorType;
import com.checkbook.elibrary.dto.ELibraryResponse;
import com.checkbook.elibrary.repository.ELibraryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@RequiredArgsConstructor
@Service
public class ELibraryService {

    private final ELibraryRepository eLibraryRepository;

    @Transactional(readOnly = true)
    public List<ELibraryResponse> readLibraries(
            String region,
            String vendorType,
            String keyword
    ) {
        String normalizedRegion = normalize(region);
        String normalizedVendorType = normalize(vendorType);
        String normalizedKeyword = normalize(keyword);

        validateRegion(normalizedRegion);
        VendorType vendorTypeEnum = parseVendorType(normalizedVendorType);

        return eLibraryRepository.findAllByFilter(
                        ELibraryStatus.ACTIVE,
                        normalizedRegion,
                        vendorTypeEnum,
                        normalizedKeyword
                ).stream()
                .map(ELibraryResponse::from)
                .toList();
    }

    private void validateRegion(String region) {
        if (region != null && !region.matches("\\d{1,2}")) {
            throw new BusinessException(ErrorCode.INVALID_REGION_CODE);
        }
    }

    private VendorType parseVendorType(String vendorType) {
        if (vendorType == null) {
            return null;
        }

        try {
            return VendorType.valueOf(vendorType.toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.INVALID_VENDOR_TYPE);
        }
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }

        String trimmedValue = value.trim();
        if (trimmedValue.isEmpty()) {
            return null;
        }

        return trimmedValue;
    }
}
