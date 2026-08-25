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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.liquibase.contexts=base",
    "security.jwt.secret=test-secret-for-brasil-drama-must-have-32-bytes",
    "rewards.zone-id=America/Sao_Paulo"
})
@AutoConfigureMockMvc
class RewardsContractTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;

    @Test
    void checkInIsServerAuthoritativeAndIdempotent() throws Exception {
        var session = register("checkin");

        mvc.perform(get("/v1/rewards/overview").header("Authorization", bearer(session.token())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.bonusBalance").value(0))
            .andExpect(jsonPath("$.vipPointsBalance").value(0))
            .andExpect(jsonPath("$.checkIn.eligible").value(true));

        mvc.perform(post("/v1/rewards/check-in")
                .header("Authorization", bearer(session.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"operationKey\":\"checkin-op-1\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accepted").value(true))
            .andExpect(jsonPath("$.bonusBalance").value(30))
            .andExpect(jsonPath("$.overview.checkIn.claimedToday").value(true));

        mvc.perform(post("/v1/rewards/check-in")
                .header("Authorization", bearer(session.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"operationKey\":\"checkin-op-1\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accepted").value(true))
            .andExpect(jsonPath("$.bonusBalance").value(30));

        mvc.perform(post("/v1/rewards/check-in")
                .header("Authorization", bearer(session.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"operationKey\":\"checkin-op-other\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accepted").value(false))
            .andExpect(jsonPath("$.bonusBalance").value(30));
    }

    @Test
    void missionClaimCreditsOnlyConfiguredLedgerAndCannotDuplicate() throws Exception {
        var session = register("mission");
        var missionId = "vip-mission-" + System.nanoTime();
        jdbc.update("insert into reward_mission(id,title,description,reward_type,reward_amount,target,enabled) values (?,?,?,?,?,?,true)",
            missionId, "Missão VIP", "Missão técnica de teste", "VIP_POINTS", 120L, 1L);
        jdbc.update("insert into user_mission(user_id,mission_id,progress,status,updated_at) values (?,?,1,'COMPLETED',now())",
            session.userId(), missionId);

        mvc.perform(post("/v1/rewards/missions/{missionId}/claim", missionId)
                .header("Authorization", bearer(session.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"operationKey\":\"mission-claim-1\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accepted").value(true))
            .andExpect(jsonPath("$.bonusBalance").value(0))
            .andExpect(jsonPath("$.vipPointsBalance").value(120));

        assertEquals("CLAIMED", jdbc.queryForObject(
            "select status from user_mission where user_id=? and mission_id=?",
            String.class, session.userId(), missionId
        ));

        mvc.perform(post("/v1/rewards/missions/{missionId}/claim", missionId)
                .header("Authorization", bearer(session.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"operationKey\":\"mission-claim-1\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accepted").value(true))
            .andExpect(jsonPath("$.vipPointsBalance").value(120));
    }

    @Test
    void incompleteMissionCannotBeClaimed() throws Exception {
        var session = register("incomplete");
        var missionId = "incomplete-" + System.nanoTime();
        jdbc.update("insert into reward_mission(id,title,description,reward_type,reward_amount,target,enabled) values (?,?,?,?,?,?,true)",
            missionId, "Incompleta", "Ainda não concluída", "BONUS", 20L, 3L);
        jdbc.update("insert into user_mission(user_id,mission_id,progress,status,updated_at) values (?,?,1,'IN_PROGRESS',now())",
            session.userId(), missionId);

        mvc.perform(post("/v1/rewards/missions/{missionId}/claim", missionId)
                .header("Authorization", bearer(session.token()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"operationKey\":\"invalid-claim\"}"))
            .andExpect(status().isConflict());
    }

    private Session register(String prefix) throws Exception {
        var email = prefix + "-" + System.nanoTime() + "@example.com";
        var body = mvc.perform(post("/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"displayName":"Rewards Test","email":"%s","password":"senha-segura-123"}
                    """.formatted(email)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(body);
        return new Session(json.get("accessToken").asText(), java.util.UUID.fromString(json.get("user").get("id").asText()));
    }

    private static String bearer(String token) { return "Bearer " + token; }
    private record Session(String token, java.util.UUID userId) {}
}
