package com.apisentinel.registry;

import com.apisentinel.shared.ApiErrors;
import com.apisentinel.shared.ClusterHttp;
import com.apisentinel.shared.EventTopology;
import com.apisentinel.shared.Outbox;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.support.TransactionTemplate;

@EnableScheduling
@SpringBootApplication
@Import({ApiErrors.class, ClusterHttp.class})
public class RegistryApplication {
    public static void main(String[] args) {
        SpringApplication.run(RegistryApplication.class, args);
    }

    @Bean
    Declarables sentinelTopology() { return EventTopology.topology(); }

    @Bean
    Outbox registryOutbox(JdbcClient db, RabbitTemplate rabbit, ObjectMapper json, TransactionTemplate tx) {
        return new Outbox(db, rabbit, json, tx);
    }
}
