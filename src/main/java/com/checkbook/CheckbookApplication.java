package com.checkbook;

import com.checkbook.common.config.JsoupTransport;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class CheckbookApplication {

	public static void main(String[] args) {
		JsoupTransport.useLegacyHttpUrlConnection();
		SpringApplication.run(CheckbookApplication.class, args);
	}

}
