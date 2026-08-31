package br.com.brasildrama;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// Scheduling habilitado para a retentativa de confirmação de compra na Google
// Play (GooglePurchaseService.retryPendingAcknowledgements). Em execução com
// múltiplas instâncias a rotina roda em todas; é idempotente por desenho — a
// Google responde 400 para compra já confirmada, tratado como sucesso.
@EnableScheduling
@SpringBootApplication
public class BackendApplication {
    public static void main(String[] args) {
        SpringApplication.run(BackendApplication.class, args);
    }
}
