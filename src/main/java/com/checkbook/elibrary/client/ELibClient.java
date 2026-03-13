package com.checkbook.elibrary.client;

import com.checkbook.elibrary.domain.VendorType;
import com.checkbook.elibrary.dto.ELibrarySearchResponse;

import java.util.List;

public interface ELibClient {

    VendorType getVendorType();

    List<ELibrarySearchResponse.ELibraryBook> search(String baseUrl, String keyword);
}
