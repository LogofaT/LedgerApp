package ledger.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Flyway and Spring AMQP bring Jackson 2 transitively in Boot 4. Keep a
 * deliberately named application mapper for persisted JSON and raw payments;
 * Spring MVC uses Boot 4's configured Jackson 3 HTTP converter.
 */
@Configuration
public class LegacyJsonConfig {
    @Bean
    ObjectMapper ledgerObjectMapper() {
        return JsonMapper.builder().defaultPropertyInclusion(
                JsonInclude.Value.construct(JsonInclude.Include.NON_NULL,JsonInclude.Include.NON_NULL)).build();
    }
}
