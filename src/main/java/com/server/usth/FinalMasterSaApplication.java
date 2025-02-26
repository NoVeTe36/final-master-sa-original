package com.server.usth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;

@SpringBootApplication
public class FinalMasterSaApplication {

    @Bean
    public Registry rmiRegistry() throws Exception {
        return LocateRegistry.createRegistry(1099);
    }

    public static void main(String[] args) {
        SpringApplication.run(FinalMasterSaApplication.class, args);
    }

}
