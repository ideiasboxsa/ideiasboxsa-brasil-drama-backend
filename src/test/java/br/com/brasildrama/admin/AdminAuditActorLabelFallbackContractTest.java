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

class AdminAuditActorLabelFallbackContractTest {
    @Test
    void actorCatalogUsesNameThenEmailThenGenericLabel() {
        var operators = mock(AdminOperatorRepository.class);
        var logs = mock(AdminAuditLogRepository.class);
        var admin = operator(AdminRole.SUPER_ADMIN, "Admin", "admin@brasildrama.com.br");
        var named = operator(AdminRole.EDITOR, "  Maria  ", "maria@brasildrama.com.br");
        var emailOnly = operator(AdminRole.EDITOR, "   ", "editor@brasildrama.com.br");
        var generic = operator(AdminRole.EDITOR, null, "   ");

        when(operators.findById(admin.id)).thenReturn(Optional.of(admin));
        when(logs.findAllByOrderByCreatedAtDesc(any(PageRequest.class))).thenReturn(List.of(
            audit(named.id), audit(emailOnly.id), audit(generic.id)
        ));
        when(operators.findAllById(any())).thenReturn(List.of(named, emailOnly, generic));

        Principal principal = () -> admin.id.toString();
        var response = new AdminAuditApi(operators, logs).actors(principal);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        var body = (List<?>) response.getBody();
        assertThat(body).hasSize(3);
        assertThat(body.stream().map(item -> ((AdminAuditApi.ActorView) item).displayName()))
            .containsExactly("editor@brasildrama.com.br", "Operador", "Maria");
    }

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
