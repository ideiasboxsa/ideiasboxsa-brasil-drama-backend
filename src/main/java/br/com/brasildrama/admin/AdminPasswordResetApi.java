package br.com.brasildrama.admin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/admin/auth/password")
public class AdminPasswordResetApi {
    private final AdminPasswordResetService service;

    public AdminPasswordResetApi(AdminPasswordResetService service) {
        this.service = service;
    }

    @PostMapping("/forgot")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void forgot(@Valid @RequestBody ForgotRequest request) {
        service.request(request.email());
    }

    @PostMapping("/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void reset(@Valid @RequestBody ResetRequest request) {
        service.confirm(request.token(), request.newPassword());
    }

    record ForgotRequest(@Email @NotBlank String email) {}
    record ResetRequest(@NotBlank String token, @NotBlank @Size(min = 12, max = 128) String newPassword) {}
}
