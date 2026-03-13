package com.checkbook.elibrary.client;

import com.checkbook.common.exception.BusinessException;
import com.checkbook.common.exception.ErrorCode;
import com.checkbook.elibrary.domain.VendorType;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class ELibClientResolver {

    private final List<ELibClient> clients;
    private Map<VendorType, ELibClient> clientMap;

    @PostConstruct
    void init() {
        Map<VendorType, ELibClient> resolvedClients = new EnumMap<>(VendorType.class);
        for (ELibClient client : clients) {
            resolvedClients.put(client.getVendorType(), client);
        }
        this.clientMap = Map.copyOf(resolvedClients);
    }

    public ELibClient resolve(VendorType vendorType) {
        ELibClient client = clientMap.get(vendorType);
        if (client == null) {
            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
        return client;
    }
}
