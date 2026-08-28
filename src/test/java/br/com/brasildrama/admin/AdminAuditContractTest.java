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
import static org.mockito.Mockito.*;

class AdminAuditContractTest {
    @Test
    void filtersByActionAndEnrichesActorWithoutNPlusOne() {
        var operators = mock(AdminOperatorRepository.class);
        var logs = mock(AdminAuditLogRepository.class);
        var actor = operator(AdminRole.SUPER_ADMIN, "Admin", "admin@brasildrama.com.br");
        var source = operator(AdminRole.CONTENT_EDITOR, "Editor Drama", "editor@brasildrama.com.br");
        var log = audit(source.id, "OPERATOR_INVITED");
        when(operators.findById(actor.id)).thenReturn(Optional.of(actor));
        when(logs.findAllByActionOrderByCreatedAtDesc(eq("OPERATOR_INVITED"), any(PageRequest.class))).thenReturn(List.of(log));
        when(operators.findAllById(any())).thenReturn(List.of(source));

        var response = new AdminAuditApi(operators, logs).latest(principal(actor.id), "OPERATOR_INVITED", null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        var body = (List<?>) response.getBody();
        assertThat(body).hasSize(1);
        var view = (AdminAuditApi.AuditView) body.getFirst();
        assertThat(view.actorDisplayName()).isEqualTo("Editor Drama");
        assertThat(view.actorEmail()).isEqualTo("editor@brasildrama.com.br");
        verify(logs).findAllByActionOrderByCreatedAtDesc(eq("OPERATOR_INVITED"), any(PageRequest.class));
        verify(operators).findAllById(any());
    }

    @Test
    void filtersByActorId() {
        var operators = mock(AdminOperatorRepository.class);
        var logs = mock(AdminAuditLogRepository.class);
        var actor = operator(AdminRole.SUPER_ADMIN, "Admin", "admin@brasildrama.com.br");
        var source = operator(AdminRole.CONTENT_EDITOR, "Editor Drama", "editor@brasildrama.com.br");
        var log = audit(source.id, "OPERATOR_ROLE_CHANGED");
        when(operators.findById(actor.id)).thenReturn(Optional.of(actor));
        when(logs.findAllByActorOperatorIdOrderByCreatedAtDesc(eq(source.id), any(PageRequest.class))).thenReturn(List.of(log));
        when(operators.findAllById(any())).thenReturn(List.of(source));

        var response = new AdminAuditApi(operators, logs).latest(principal(actor.id), null, source.id);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        var body = (List<?>) response.getBody();
        assertThat(body).hasSize(1);
        var view = (AdminAuditApi.AuditView) body.getFirst();
        assertThat(view.actorOperatorId()).isEqualTo(source.id);
        verify(logs).findAllByActorOperatorIdOrderByCreatedAtDesc(eq(source.id), any(PageRequest.class));
    }

    @Test
    void rejectsNonSuperAdmin() {
        var operators = mock(AdminOperatorRepository.class);
        var logs = mock(AdminAuditLogRepository.class);
        var actor = operator(AdminRole.CONTENT_EDITOR, "Editor", "editor@brasildrama.com.br");
        when(operators.findById(actor.id)).thenReturn(Optional.of(actor));
        var response = new AdminAuditApi(operators, logs).latest(principal(actor.id), null, null);
        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verifyNoInteractions(logs);
    }

    private static Principal principal(UUID id) { return () -> id.toString(); }
    private static AdminOperator operator(AdminRole role, String name, String email) { var o = new AdminOperator(); o.id = UUID.randomUUID(); o.role = role; o.displayName = name; o.email = email; o.active = true; return o; }
    private static AdminAuditLog audit(UUID actorId, String action) { var l = new AdminAuditLog(); l.id = UUID.randomUUID(); l.actorOperatorId = actorId; l.action = action; l.entityType = "ADMIN_OPERATOR"; l.entityId = UUID.randomUUID().toString(); l.summary = "evento"; l.createdAt = Instant.now(); return l; }
}
