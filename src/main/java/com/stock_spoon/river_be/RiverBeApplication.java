package com.stock_spoon.river_be;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@org.springframework.context.annotation.Profile("!execution")
@SpringBootApplication
@EnableScheduling
public class RiverBeApplication {
	public static void main(String[] args) {
		var app = new SpringApplication(RiverBeApplication.class);
        app.setAdditionalProfiles("api");
        app.run(args);

	}

}