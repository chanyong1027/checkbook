package com.checkbook.elibrary.client;

import com.checkbook.elibrary.domain.VendorType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KyoboELibClientTest {

    private final KyoboELibClient client = new KyoboELibClient();

    @Test
    void getVendorTypeReturnsKyobo() {
        assertThat(client.getVendorType()).isEqualTo(VendorType.KYOBO);
    }

    @Test
    void searchUnreachableUrlReturnsEmptyList() {
        assertThat(client.search("http://127.0.0.1:1", "자바")).isNotNull().isEmpty();
    }

    @Test
    void implementsELibClient() {
        assertThat(client).isInstanceOf(ELibClient.class);
    }
}
