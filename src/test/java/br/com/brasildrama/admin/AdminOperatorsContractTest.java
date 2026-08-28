package br.com.brasildrama.admin;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminOperatorsContractTest {

    @Test
    void superAdminCanListOperatorsWithoutSensitivePasswordData() {
        var repository = mock(AdminOperatorRepository.class);
        var actor = operator("admin@brasildrama.com", "Admin", AdminRole.SUPER_ADMIN, true);
        var editor = operator("editor@brasildrama.com", "Editor", AdminRole.EDITOR, true);
        when(repository.findById(actor.id)).thenReturn(Optional.of(actor));
        when(repository.findAll()).thenReturn(List.of(editor, actor));
        var api = new AdminOperatorsApi(repository);

        var response = api.list(() -> actor.id.toString());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).asList().hasSize(2);
    }

    @Test
    void nonSuperAdminCannotListOperators() {
        var repository = mock(AdminOperatorRepository.class);
        var actor = operator("support@brasildrama.com", "Suporte", AdminRole.SUPPORT, true);
        when(repository.findById(actor.id)).thenReturn(Optional.of(actor));
        var api = new AdminOperatorsApi(repository);

        var response = api.list(() -> actor.id.toString());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(repository, never()).findAll();
    }

    @Test
    void blocksSelfRoleOrStatusChanges() {
        var repository = mock(AdminOperatorRepository.class);
        var actor = operator("admin@brasildrama.com", "Admin", AdminRole.SUPER_ADMIN, true);
        when(repository.findById(actor.id)).thenReturn(Optional.of(actor));
        var api = new AdminOperatorsApi(repository);

        var response = api.update(
            actor.id,
            new AdminOperatorsApi.UpdateOperatorRequest("EDITOR", false),
            () -> actor.id.toString()
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isEqualTo(new AdminOperatorsApi.ErrorResponse("SELF_ACCESS_CHANGE_BLOCKED"));
        verify(repository, never()).save(any());
    }

    @Test
    void superAdminCanChangeAnotherOperatorsRoleAndStatus() {
        var repository = mock(AdminOperatorRepository.class);
        var actor = operator("admin@brasildrama.com", "Admin", AdminRole.SUPER_ADMIN, true);
        var target = operator("editor@brasildrama.com", "Editor", AdminRole.EDITOR, true);
        when(repository.findById(actor.id)).thenReturn(Optional.of(actor));
        when(repository.findById(target.id)).thenReturn(Optional.of(target));
        when(repository.save(any(AdminOperator.class))).thenAnswer(invocation -> invocation.getArgument(0));
        var api = new AdminOperatorsApi(repository);

        var response = api.update(
            target.id,
            new AdminOperatorsApi.UpdateOperatorRequest("CONTENT_ADMIN", false),
            () -> actor.id.toString()
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(target.role).isEqualTo(AdminRole.CONTENT_ADMIN);
        assertThat(target.active).isFalse();
        verify(repository).save(target);
    }

    private static AdminOperator operator(String email, String displayName, AdminRole role, boolean active) {
        var operator = new AdminOperator();
        operator.id = UUID.randomUUID();
        operator.email = email;
        operator.displayName = displayName;
        operator.passwordHash = "secret-hash-that-must-never-be-returned";
        operator.role = role;
        operator.active = active;
        operator.createdAt = Instant.parse("2026-08-01T10:00:00Z");
        operator.updatedAt = Instant.parse("2026-08-20T10:00:00Z");
        return operator;
    }
}
