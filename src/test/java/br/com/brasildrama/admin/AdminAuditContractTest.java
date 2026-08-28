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
        var source = operator(AdminRole.EDITOR, "Editor Drama", "editor@brasildrama.com.br");
        var matching = audit(source.id, "OPERATOR_INVITED");
        var ignored = audit(source.id, "OPERATOR_ROLE_CHANGED");
        when(operators.findById(actor.id)).thenReturn(Optional.of(actor));
        when(logs.findAllByOrderByCreatedAtDesc(any(PageRequest.class))).thenReturn(List.of(matching, ignored));
        when(operators.findAllById(any())).thenReturn(List.of(source));

        var response = new AdminAuditApi(operators, logs).latest(principal(actor.id), " operator_invited ", null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        var body = (List<?>) response.getBody();
        assertThat(body).hasSize(1);
        var view = (AdminAuditApi.AuditView) body.getFirst();
        assertThat(view.action()).isEqualTo("OPERATOR_INVITED");
        assertThat(view.actorDisplayName()).isEqualTo("Editor Drama");
        assertThat(view.actorEmail()).isEqualTo("editor@brasildrama.com.br");
        verify(logs).findAllByOrderByCreatedAtDesc(any(PageRequest.class));
        verify(operators).findAllById(any());
    }

    @Test
    void filtersByActorId() {
        var operators = mock(AdminOperatorRepository.class);
        var logs = mock(AdminAuditLogRepository.class);
        var actor = operator(AdminRole.SUPER_ADMIN, "Admin", "admin@brasildrama.com.br");
        var source = operator(AdminRole.EDITOR, "Editor Drama", "editor@brasildrama.com.br");
        var other = operator(AdminRole.SUPPORT, "Suporte", "support@brasildrama.com.br");
        when(operators.findById(actor.id)).thenReturn(Optional.of(actor));
        when(logs.findAllByOrderByCreatedAtDesc(any(PageRequest.class))).thenReturn(List.of(
            audit(source.id, "OPERATOR_ROLE_CHANGED"), audit(other.id, "OPERATOR_INVITED")
        ));
        when(operators.findAllById(any())).thenReturn(List.of(source, other));

        var response = new AdminAuditApi(operators, logs).latest(principal(actor.id), null, source.id);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        var body = (List<?>) response.getBody();
        assertThat(body).hasSize(1);
        assertThat(((AdminAuditApi.AuditView) body.getFirst()).actorOperatorId()).isEqualTo(source.id);
    }

    @Test
    void exposesServerDrivenActionCatalogToSuperAdmin() {
        var operators = mock(AdminOperatorRepository.class);
        var logs = mock(AdminAuditLogRepository.class);
        var actor = operator(AdminRole.SUPER_ADMIN, "Admin", "admin@brasildrama.com.br");
        when(operators.findById(actor.id)).thenReturn(Optional.of(actor));

        var response = new AdminAuditApi(operators, logs).actions(principal(actor.id));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        var body = (List<?>) response.getBody();
        assertThat(body).isNotEmpty();
        var first = (AdminAuditApi.ActionView) body.getFirst();
        assertThat(first.action()).isNotBlank();
        assertThat(first.label()).isNotBlank();
        verifyNoInteractions(logs);
    }

    @Test
    void exposesOnlyAuditedActorsSortedByDisplayName() {
        var operators = mock(AdminOperatorRepository.class);
        var logs = mock(AdminAuditLogRepository.class);
        var actor = operator(AdminRole.SUPER_ADMIN, "Admin", "admin@brasildrama.com.br");
        var zeta = operator(AdminRole.EDITOR, "Zeta", "zeta@brasildrama.com.br");
        var alpha = operator(AdminRole.SUPPORT, "Alpha", "alpha@brasildrama.com.br");
        when(operators.findById(actor.id)).thenReturn(Optional.of(actor));
        when(logs.findAllByOrderByCreatedAtDesc(any(PageRequest.class))).thenReturn(List.of(
            audit(zeta.id, "OPERATOR_INVITED"), audit(alpha.id, "PROFILE_DISPLAY_NAME_CHANGED")
        ));
        when(operators.findAllById(any())).thenReturn(List.of(zeta, alpha));

        var response = new AdminAuditApi(operators, logs).actors(principal(actor.id));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        var body = (List<?>) response.getBody();
        assertThat(body).hasSize(2);
        assertThat(((AdminAuditApi.ActorView) body.get(0)).displayName()).isEqualTo("Alpha");
        assertThat(((AdminAuditApi.ActorView) body.get(1)).displayName()).isEqualTo("Zeta");
        verify(logs).findAllByOrderByCreatedAtDesc(any(PageRequest.class));
        verify(operators).findAllById(any());
    }

    @Test
    void rejectsNonSuperAdmin() {
        var operators = mock(AdminOperatorRepository.class);
        var logs = mock(AdminAuditLogRepository.class);
        var actor = operator(AdminRole.EDITOR, "Editor", "editor@brasildrama.com.br");
        when(operators.findById(actor.id)).thenReturn(Optional.of(actor));
        var response = new AdminAuditApi(operators, logs).latest(principal(actor.id), null, null);
        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verifyNoInteractions(logs);
    }

    @Test
    void rejectsActionCatalogForNonSuperAdmin() {
        var operators = mock(AdminOperatorRepository.class);
        var logs = mock(AdminAuditLogRepository.class);
        var actor = operator(AdminRole.SUPPORT, "Suporte", "support@brasildrama.com.br");
        when(operators.findById(actor.id)).thenReturn(Optional.of(actor));
        var response = new AdminAuditApi(operators, logs).actions(principal(actor.id));
        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verifyNoInteractions(logs);
    }

    @Test
    void rejectsActorCatalogForNonSuperAdmin() {
        var operators = mock(AdminOperatorRepository.class);
        var logs = mock(AdminAuditLogRepository.class);
        var actor = operator(AdminRole.EDITOR, "Editor", "editor@brasildrama.com.br");
        when(operators.findById(actor.id)).thenReturn(Optional.of(actor));
        var response = new AdminAuditApi(operators, logs).actors(principal(actor.id));
        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verifyNoInteractions(logs);
    }

    private static Principal principal(UUID id) { return () -> id.toString(); }
    private static AdminOperator operator(AdminRole role, String name, String email) { var o = new AdminOperator(); o.id = UUID.randomUUID(); o.role = role; o.displayName = name; o.email = email; o.active = true; return o; }
    private static AdminAuditLog audit(UUID actorId, String action) { var l = new AdminAuditLog(); l.id = UUID.randomUUID(); l.actorOperatorId = actorId; l.action = action; l.entityType = "ADMIN_OPERATOR"; l.entityId = UUID.randomUUID().toString(); l.summary = "evento"; l.createdAt = Instant.now(); return l; }
}
