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

class AdminAuditFilterNormalizationTest {
    @Test
    void blankActionFilterBehavesAsNoActionFilter() {
        var operators = mock(AdminOperatorRepository.class);
        var logs = mock(AdminAuditLogRepository.class);
        var admin = operator(AdminRole.SUPER_ADMIN, "Admin", "admin@brasildrama.com.br");
        var editor = operator(AdminRole.EDITOR, "Editor", "editor@brasildrama.com.br");
        when(operators.findById(admin.id)).thenReturn(Optional.of(admin));
        when(logs.findAllByOrderByCreatedAtDesc(any(PageRequest.class))).thenReturn(List.of(audit(editor.id, "OPERATOR_INVITED"), audit(editor.id, "OPERATOR_ROLE_CHANGED")));
        when(operators.findAllById(any())).thenReturn(List.of(editor));

        var response = new AdminAuditApi(operators, logs).latest(principal(admin.id), "   ", null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat((List<?>) response.getBody()).hasSize(2);
    }

    private static Principal principal(UUID id) { return () -> id.toString(); }
    private static AdminOperator operator(AdminRole role, String name, String email) { var o = new AdminOperator(); o.id = UUID.randomUUID(); o.role = role; o.displayName = name; o.email = email; o.active = true; return o; }
    private static AdminAuditLog audit(UUID actorId, String action) { var l = new AdminAuditLog(); l.id = UUID.randomUUID(); l.actorOperatorId = actorId; l.action = action; l.entityType = "ADMIN_OPERATOR"; l.entityId = UUID.randomUUID().toString(); l.summary = "evento"; l.createdAt = Instant.now(); return l; }
}
