package com.banque;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling // requis : active la synchronisation Edge -> Cloud (EdgeCloudSyncService)
public class BanqueApplication {

	public static void main(String[] args) {
		SpringApplication.run(BanqueApplication.class, args);
                //"C:\apache-maven-3.9.9\bin\mvn.cmd" clean install -DskipTests
                //java -jar G:\banque\target\banque-0.0.1-SNAPSHOT.jar
	}

}