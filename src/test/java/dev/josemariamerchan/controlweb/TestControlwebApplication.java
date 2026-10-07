package dev.josemariamerchan.controlweb;

import org.springframework.boot.SpringApplication;

public class TestControlwebApplication {

	public static void main(String[] args) {
		SpringApplication.from(ControlwebApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
