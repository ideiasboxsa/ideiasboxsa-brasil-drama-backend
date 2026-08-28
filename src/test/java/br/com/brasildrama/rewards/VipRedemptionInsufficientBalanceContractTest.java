package br.com.brasildrama.rewards;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "spring.liquibase.contexts=base",
    "security.jwt.secret=test-secret-for-brasil-drama-must-have-32-bytes"
})
@AutoConfigureMockMvc
class VipRedemptionInsufficientBalanceContractTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;

    @Test
    void insufficientPointsDoNotCreateRedemptionOrDebit() throws Exception {
        var session = register();
        String optionId = "vip-insufficient-" + System.nanoTime();
        jdbc.update(
            "insert into vip_redemption_option(id,label,required_vip_points,vip_days,enabled,display_order) values (?,?,?,?,true,1)",
            optionId, "VIP saldo insuficiente", 100L, 7
        );
        jdbc.update(
            "insert into reward_ledger(id,user_id,ledger_type,operation_key,amount,reference_type,created_at) values (?,?,?,?,?,'TEST',now())",
            UUID.randomUUID(), session.userId(), "VIP_POINTS", "vip-seed-" + optionId, 80L
        );

        mvc.perform(post("/v1/rewards/vip/{optionId}/redeem", optionId)
                .header("Authorization", "Bearer " + session.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"operationKey\":\"vip-insufficient-once\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.detail").value("Insufficient VIP points"));

        assertThat(jdbc.queryForObject(
            "select count(*) from vip_redemption where user_id=?",
            Long.class,
            session.userId()
        )).isZero();
        assertThat(jdbc.queryForObject(
            "select coalesce(sum(amount),0) from reward_ledger where user_id=? and ledger_type='VIP_POINTS'",
            Long.class,
            session.userId()
        )).isEqualTo(80L);
    }

    private Session register() throws Exception {
        String email = "vip-insufficient-" + System.nanoTime() + "@example.com";
        String body = mvc.perform(post("/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"displayName":"VIP Insufficient Test","email":"%s","password":"senha-segura-123"}
                    """.formatted(email)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(body);
        return new Session(json.get("accessToken").asText(), UUID.fromString(json.get("user").get("id").asText()));
    }

    private record Session(String token, UUID userId) {}
}
