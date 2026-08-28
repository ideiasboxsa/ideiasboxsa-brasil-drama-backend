package br.com.brasildrama.admin;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminMeContractTest {
    @Test
    void updatesDisplayNameForCurrentActiveOperator() {
        var repository = mock(AdminOperatorRepository.class);
        var operator = operator("Nome Antigo");
        when(repository.findById(operator.id)).thenReturn(Optional.of(operator));
        when(repository.save(any(AdminOperator.class))).thenAnswer(invocation -> invocation.getArgument(0));
        var api = new AdminMeApi(repository);

        var response = api.update(() -> operator.id.toString(), new AdminMeApi.UpdateProfileRequest("  Novo Nome  "));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(operator.displayName).isEqualTo("Novo Nome");
        assertThat(response.getBody()).isInstanceOf(AdminMeApi.AdminProfile.class);
        assertThat(((AdminMeApi.AdminProfile) response.getBody()).displayName()).isEqualTo("Novo Nome");
        verify(repository).save(operator);
    }

    @Test
    void rejectsInvalidDisplayNameWithoutPersisting() {
        var repository = mock(AdminOperatorRepository.class);
        var operator = operator("Nome Atual");
        when(repository.findById(operator.id)).thenReturn(Optional.of(operator));
        var api = new AdminMeApi(repository);

        var response = api.update(() -> operator.id.toString(), new AdminMeApi.UpdateProfileRequest(" "));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(operator.displayName).isEqualTo("Nome Atual");
        assertThat(response.getBody()).isEqualTo(new AdminMeApi.ValidationError("DISPLAY_NAME_INVALID"));
    }

    @Test
    void doesNotExposeInactiveOperatorAsCurrentSession() {
        var repository = mock(AdminOperatorRepository.class);
        var operator = operator("Operador Inativo");
        operator.active = false;
        when(repository.findById(operator.id)).thenReturn(Optional.of(operator));
        var api = new AdminMeApi(repository);

        var response = api.me(() -> operator.id.toString());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private static AdminOperator operator(String displayName) {
        var operator = new AdminOperator();
        operator.id = UUID.randomUUID();
        operator.email = "operator@brasildrama.com.br";
        operator.displayName = displayName;
        operator.passwordHash = "hash";
        operator.role = AdminRole.SUPER_ADMIN;
        operator.active = true;
        operator.createdAt = Instant.parse("2026-08-01T10:00:00Z");
        operator.updatedAt = Instant.parse("2026-08-20T10:00:00Z");
        return operator;
    }
}
