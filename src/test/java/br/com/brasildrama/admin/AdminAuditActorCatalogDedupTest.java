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

class AdminAuditActorCatalogDedupTest {
    @Test
    void repeatedAuditEntriesExposeActorOnlyOnce() {
        var operators = mock(AdminOperatorRepository.class);
        var logs = mock(AdminAuditLogRepository.class);
        var admin = operator(AdminRole.SUPER_ADMIN, "Admin", "admin@brasildrama.com.br");
        var editor = operator(AdminRole.EDITOR, "Editor Drama", "editor@brasildrama.com.br");
        when(operators.findById(admin.id)).thenReturn(Optional.of(admin));
        when(logs.findAllByOrderByCreatedAtDesc(any(PageRequest.class))).thenReturn(List.of(
                audit(editor.id, "OPERATOR_INVITED"),
                audit(editor.id, "OPERATOR_ROLE_CHANGED"),
                audit(editor.id, "PROFILE_DISPLAY_NAME_CHANGED")
        ));
        when(operators.findAllById(any())).thenReturn(List.of(editor));

        var response = new AdminAuditApi(operators, logs).actors(principal(admin.id));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        var body = (List<?>) response.getBody();
        assertThat(body).hasSize(1);
        var actor = (AdminAuditApi.ActorView) body.getFirst();
        assertThat(actor.id()).isEqualTo(editor.id);
        assertThat(actor.displayName()).isEqualTo("Editor Drama");
    }

    private static Principal principal(UUID id) { return () -> id.toString(); }

    private static AdminOperator operator(AdminRole role, String name, String email) {
        var operator = new AdminOperator();
        operator.id = UUID.randomUUID();
        operator.role = role;
        operator.displayName = name;
        operator.email = email;
        operator.active = true;
        return operator;
    }

    private static AdminAuditLog audit(UUID actorId, String action) {
        var log = new AdminAuditLog();
        log.id = UUID.randomUUID();
        log.actorOperatorId = actorId;
        log.action = action;
        log.entityType = "ADMIN_OPERATOR";
        log.entityId = UUID.randomUUID().toString();
        log.summary = "evento";
        log.createdAt = Instant.now();
        return log;
    }
}
