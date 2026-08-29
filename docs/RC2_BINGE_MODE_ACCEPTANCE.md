# RC2 — Modo Maratona

Critérios técnicos:

- `completion` continua sendo emitido ao terminar um episódio.
- `next_episode` representa continuidade automática para o episódio seguinte.
- `binge_session` é emitido apenas quando a sessão atinge o limiar de maratona.
- O Android nunca deve avançar automaticamente para episódio premium sem entitlement.
- Autoplay desligado impede avanço automático.
- Último episódio encerra normalmente.
- Métricas administrativas usam janelas de 7, 30 e 90 dias.
- Nenhum asset faz parte desta entrega.
