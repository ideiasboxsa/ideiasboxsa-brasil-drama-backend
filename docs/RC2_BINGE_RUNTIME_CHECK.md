# Runtime gate — Modo Maratona

Antes de considerar E06 fechado em produção, validar o endpoint `/v1/admin/analytics/binge` contra PostgreSQL real nas janelas 7, 30 e 90 dias, incluindo sessões sem eventos, continuidade sem binge e sessões que atingem o limiar. CI de compilação não substitui essa validação de consulta SQL.
