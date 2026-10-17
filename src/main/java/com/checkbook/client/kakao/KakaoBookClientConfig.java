package com.checkbook.client.kakao;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

// KakaoBookClient(@Component)가 주입받을 설정 레코드를 등록한다
@Configuration
@EnableConfigurationProperties(KakaoBookProperties.class)
public class KakaoBookClientConfig {
}
