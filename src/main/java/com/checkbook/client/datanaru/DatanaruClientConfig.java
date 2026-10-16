package com.checkbook.client.datanaru;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

// DatanaruClient(@Component)가 주입받을 설정 레코드를 등록한다
@Configuration
@EnableConfigurationProperties(DatanaruProperties.class)
public class DatanaruClientConfig {
}
