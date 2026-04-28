package com.devsuperior.demo;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.security.crypto.password.PasswordEncoder;

@SpringBootApplication
public class DemoApplication implements CommandLineRunner {

	@Autowired
	private PasswordEncoder passwordEncoder;

	public static void main(String[] args) {
		SpringApplication.run(DemoApplication.class, args);
	}

	@Override
	public void run(String... args) throws Exception {
		System.out.println("ENCODE: " + passwordEncoder.encode("123456"));

		//o que acontece por baixo dos panos
		boolean result = passwordEncoder.matches("123456","$2a$10$YnBL3pqas7sb.6DIKuIwYe9iY65tNU4i2u.CcKeWAxnd3S5kCYoBa");
		System.out.println(result);
	}
}
