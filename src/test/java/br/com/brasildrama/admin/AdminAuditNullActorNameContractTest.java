package br.com.brasildrama.admin;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminAuditNullActorNameContractTest {
    @Test
    void actorCatalogHandlesOperatorWithoutDisplayName() {
        var operators = mock(AdminOperatorRepository.class);
        var logs = mock(AdminAuditLogRepository.class);
        var admin = operator(AdminRole.SUPER_ADMIN, "Admin", "admin@brasildrama.com.br");
        var unnamed = operator(AdminRole.EDITOR, null, "editor@brasildrama.com.br");
        when(operators.findById(admin.id)).thenReturn(Optional.of(admin));
        when(logs.findAllByOrderByCreatedAtDesc(any(PageRequest.class))).thenReturn(List.of(audit(unnamed.id)));
        when(operators.findAllById(any())).thenReturn(List.of(unnamed));

        var response = new AdminAuditApi(operators, logs).actors(principal(admin.id));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        var body = (List<?>) response.getBody();
        assertThat(body).hasSize(1);
        var actor = (AdminAuditApi.ActorView) body.getFirst();
        assertThat(actor.id()).isEqualTo(unnamed.id);
        assertThat(actor.displayName()).isEqualTo("editor@brasildrama.com.br");
        assertThat(actor.email()).isEqualTo("editor@brasildrama.com.br");
    }

    private static Principal principal(UUID id) { return () -> id.toString(); }
    private static AdminOperator operator(AdminRole role, String name, String email) {
        var value = new AdminOperator();
        value.id = UUID.randomUUID();
        value.role = role;
        value.displayName = name;
        value.email = email;
        value.active = true;
        return value;
    }
    private static AdminAuditLog audit(UUID actorId) {
        var value = new AdminAuditLog();
        value.id = UUID.randomUUID();
        value.actorOperatorId = actorId;
        value.action = "OPERATOR_INVITED";
        value.entityType = "ADMIN_OPERATOR";
        value.summary = "evento";
        value.createdAt = Instant.now();
        return value;
    }
}
