package dev.josemariamerchan.controlweb;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ControlwebApplication {

	public static void main(String[] args) {
		SpringApplication.run(ControlwebApplication.class, args);
	}

}
