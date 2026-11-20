package com.chubb.claims;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ClaimsServiceApplicationTests {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void migrationsApplyAndSeedSixMarkets() {
        Integer markets = jdbc.queryForObject("SELECT count(*) FROM claims.market", Integer.class);
        assertThat(markets).isEqualTo(6);
    }
}
