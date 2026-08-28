# D2 — Fluxo principal E2E Backend

## Objetivo
Congelar o contrato mínimo necessário para Android e Studio concluírem o fluxo conteúdo → catálogo → reprodução.

## Contrato obrigatório
1. Catálogo público entrega dramas/episódios publicados sem exigir autenticação indevida.
2. Home/Descoberta usa apenas conteúdo publicável e identificadores persistentes.
3. Drama selecionado possui episódio reproduzível ou resposta explícita de indisponibilidade.
4. URLs/metadados necessários ao player são server-authoritative.
5. Progresso, likes, favoritos e entitlement mantêm seus contratos existentes e idempotência.
6. Conteúdo não publicado não pode vazar para catálogo público.

## Evidência para fechar D2
- Backend CI GREEN.
- Imagem DEV publicada.
- Smoke HTTP DEV do catálogo/drama/episódio/player.
- Conteúdo criado/publicado no Studio aparece no contrato público esperado.
- Nenhum P0/P1 no caminho conteúdo → reprodução.

## Fora do D2
Novos recursos administrativos e expansão de analytics ficam pós-RC salvo bloqueio do fluxo principal.
