package com.checkbook.client.millie;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

// MillieClient·MillieBookService가 주입받을 설정 레코드를 등록한다
@Configuration
@EnableConfigurationProperties(MillieProperties.class)
public class MillieClientConfig {
}
