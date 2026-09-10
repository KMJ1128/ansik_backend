package com.kmj.ansik;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AnsikApplication {

	public static void main(String[] args) {
		SpringApplication.run(AnsikApplication.class, args);
	}

}
