# 05 — Matriz de Entregas e Lacunas

Data: 2026-08-31 · Inventário item a item, com evidência

---

## Legenda

| Símbolo | Significado |
|---|---|
| ✅ | Entregue e verificável no código |
| 🟡 | Entregue parcialmente, ou entregue e não homologado |
| ⚠️ | Existe, mas com defeito ou dependência não resolvida |
| ❌ | Não existe |
| 🔒 | Bloqueia o lançamento |

---

## 1. Android — jornada do usuário

| Capacidade | Status | Evidência / observação |
|---|---|---|
| Home personalizada | ✅ | `PersonalizedHomeScreen.kt` |
| Descoberta / trilhos | ✅ | `DiscoveryHomeScreen.kt` |
| Navegação por categoria | ✅ | `CategoryBrowserScreen.kt` |
| Busca | ✅ | `SearchCatalogScreenV2.kt` |
| Player vertical cinematográfico | ✅ | `CinematicPlayerScreen.kt`, Media3 1.8.0 |
| Modo maratona (E06) | 🟡 | Integrado no commit `f73c068`; **não homologado** — inatingível em DEV |
| Avanço automático de episódio | ⚠️ | Lógica inline no Composable; `AutomaticEpisodeAdvance` órfã |
| Favoritos (servidor) | ✅ | `ServerFavoritesScreen.kt` |
| Curtidas | ✅ | Suporta visitante e autenticado |
| Continuar assistindo | ✅ | Visitante e autenticado |
| Histórico de reprodução | ✅ | `PUT /v1/me/history/{dramaId}` |
| Perfil | ✅ | `ProfileScreen.kt` |
| Preferências de reprodução | ✅ | |
| Preferências de notificação | ✅ | `NotificationPreferencesScreen.kt` |
| Central de recompensas | ✅ | `RewardsCenterScreen.kt` |
| Recompensas do visitante | ✅ | `GuestRewardsScreen.kt` |
| Paywall | ✅ | `PaywallScreen.kt` |
| Login e cadastro | ✅ | |
| Entrar com Google | ✅ | Credential Manager 1.6.0 |
| Recuperação de senha | ✅ | Deep link |
| Chamados de suporte | ✅ | 5 endpoints |
| Deep links | ⚠️ | `autoVerify` declarado; **`assetlinks.json` ausente** no domínio |
| **Exclusão de conta** | ❌ 🔒 | Nenhuma ocorrência em `app/src/main` |
| Modo offline | ❌ | Fora de escopo declarado |
| Picture-in-Picture | ❌ | Fora de escopo declarado |

## 2. Android — monetização

| Capacidade | Status | Evidência / observação |
|---|---|---|
| Google Play Billing | ✅ | `BillingManager.kt`, billing-ktx 9.1.0 |
| Fluxo de compra | ✅ | `PaywallScreen.kt` |
| Consumo de pacote de moedas | ✅ | Após validação no servidor |
| Confirmação de assinatura | ⚠️ | Sem retentativa em caso de falha (D-04 / F-01) |
| Restauração de compras | ✅ | SUBS + INAPP |
| Anúncio premiado | ⚠️ | Integrado; **App ID de teste do Google** (D-08 / F-02) 🔒 |
| Desbloqueio de episódio por anúncio | ✅ | `RewardedEpisodeButton.kt` |
| Carteira / saldo | ✅ | |
| Política de acesso a episódio | ✅ | `EpisodeAccessPolicy.kt`, fail-closed |
| Reconciliação de direitos | ✅ | `RightsReconciliationService.kt` |

## 3. Android — plataforma e distribuição

| Capacidade | Status | Evidência / observação |
|---|---|---|
| Flavor `dev` | ✅ | Completo, com Firebase |
| Flavor `hml` | ⚠️ | URL sem infraestrutura; Firebase vazio |
| Flavor `prod` | ⚠️ | URL sem infraestrutura; Firebase vazio 🔒 |
| Push FCM | 🟡 | Funciona em `dev`; quebrado em `hml`/`prod` |
| **Build de release** | ❌ 🔒 | Nenhum bloco `release` em `build.gradle.kts` |
| **Configuração de assinatura** | ❌ 🔒 | Nenhum `signingConfig` |
| **R8 / minificação** | ❌ | Nenhum `minifyEnabled` |
| **Geração de AAB** | ❌ 🔒 | CI só monta APK debug |
| `.gitignore` | ❌ | Ausente no repositório Android |
| **CI em `develop`** | ❌ | Workflow só em dispatch manual e `release/**` |
| Laboratório de dispositivos | 🟡 | Construído; desligado por `MOBILE_CENTER_ENABLED` |
| Crash reporting | ❌ | Firebase presente, só messaging |
| `usesCleartextTraffic` | ⚠️ | `true` em todos os flavors (D-02) |
| `allowBackup` | ⚠️ | `true` — inclui dados de sessão |

---

## 4. Backend — domínios funcionais

| Domínio | Endpoints | Testes | Status |
|---|---|---|---|
| `auth` | login, cadastro, Google, senha | 1 | ✅ |
| `catalog` | categorias, busca, ranking, dramas, curtidas | 5 | ✅ |
| `home` | home, curadoria | **0** | 🟡 sem cobertura |
| `library` | favoritos, histórico, continuar assistindo | 1 | ✅ |
| `media` | presign, multipart, confirmação | **0** | ⚠️ crítico sem cobertura |
| `monetization` | catálogo, verificação Google, restauração, assinatura | 2 | ⚠️ pouca cobertura onde há dinheiro |
| `wallet` | saldo, crédito idempotente | 1 | ✅ |
| `rewards` | check-in, missões, VIP, anúncio premiado, SSV | 8 | ✅ |
| `recommendation` | próxima obsessão, trending, feedback | **0** | 🟡 sem cobertura |
| `push` | registro de dispositivo, preferências, dispatcher FCM | **0** | 🟡 sem cobertura |
| `identity` | fusão visitante→conta | **0** | ⚠️ crítico sem cobertura |
| `analytics` | 10 superfícies de métrica | 4 | ✅ |
| `admin` | IAM, operadores, auditoria, suporte, dashboard | 15 | ✅ sobre-coberto |

## 5. Backend — plataforma

| Capacidade | Status | Evidência / observação |
|---|---|---|
| Autenticação JWT | ✅ | `JwtService.java` |
| Autorização por role | ✅ | `SecurityConfig.java:32` |
| Fronteira admin | ✅ | `/v1/admin/**` sob `hasRole("ADMIN")` |
| Bootstrap de admin | ✅ | Desligado por padrão, senha 12+ |
| Migrações Liquibase | ✅ | 36 changelogs, contextos separados |
| **Rollback de migração** | ❌ | Nenhum changeset define |
| Armazenamento S3 | ✅ | Presign, multipart, validação server-side |
| **CDN para mídia** | ❌ 🔒 | S3 direto — custo e desempenho |
| Verificação Google Play | ✅ | Android Publisher v3, real |
| **Acknowledge no servidor** | ❌ | Só no cliente, sem retentativa (F-01) |
| Verificação SSV AdMob | ✅ | ECDSA contra chaves do Google |
| Dispatcher FCM | ✅ | HTTP v1 |
| E-mail (Mailgun) | ✅ | Reset de senha administrativo |
| Trilha de auditoria | ✅ | `AdminAuditLog.java` |
| **Rate limiting** | ❌ 🔒 | Nenhuma ocorrência (D-03) |
| **Configuração CORS** | ❌ | Fora do código; dependência oculta de infraestrutura |
| **Segredo JWT sem default** | ❌ | Default embutido no profile base (D-05) |
| Profile `dev-cloud` | ✅ | |
| **Profile `prod`** | ❌ 🔒 | Não existe |
| **Deploy automatizado** | ❌ 🔒 | Só publicação de imagem; nada consome a tag |
| Health check | ✅ | `/actuator/health` com probes |
| **Métricas exportadas** | ❌ 🔒 | Só `health,info` |
| **Rastreamento distribuído** | ❌ | |
| **Log estruturado** | ❌ | Padrão do Spring, sem correlação |
| **Alertas** | ❌ 🔒 | |
| **Backup e retenção** | ❌ 🔒 | Nenhuma política descrita |
| CI | ✅ | `mvn -B verify` com PostgreSQL real |
| `mvnw` versionado | ❌ | |

---

## 6. Studio

| Capacidade | Status | Rota |
|---|---|---|
| Login e recuperação de senha | ✅ | `/login`, `/forgot-password` |
| Visão geral | ✅ | `/` |
| Gestão de conteúdo | ✅ | `/content`, `/content/dramas` |
| Workspace de série | ✅ | `/content/dramas/workspace` |
| Estúdio de episódios | ✅ | `EpisodeStudio.tsx` |
| Biblioteca de mídia | ✅ | `/media` |
| Curadoria da Home | ✅ | `/curation` |
| Próxima obsessão | ✅ | `/curation/next-obsession` |
| Recomendações | ✅ | `/curation/recommendations` |
| Monetização | ✅ | `/monetization` |
| Recompensas | ✅ | `/rewards` |
| Usuários e suporte | ✅ | `/users` |
| Operadores e IAM | ✅ | `/settings/operators` |
| Operação e auditoria | ✅ | `/operations` |
| Analytics — 10 superfícies | ✅ | `/analytics/*` |
| Envio de campanha push | ✅ | `/analytics/push` |
| **Testes** | ❌ | Zero arquivos |
| **Lockfile** | ❌ | `npm install` sem `package-lock.json` (D-07) |
| Deploy DEV | ✅ | Melhor pipeline das três frentes |
| **Deploy PROD** | ❌ 🔒 | |
| **CSP / cabeçalhos de segurança** | ❌ | Export estático; exigiria CloudFront versionado |
| Token em `sessionStorage` | ⚠️ | Sem CSP para mitigar |
| Distinção "zero" vs. "sem dados" | ❌ | M8 no plano; afeta os 10 dashboards |

---

## 7. Conformidade e requisitos de loja

**Categoria com zero itens entregues.** Nenhum artefato em nenhum dos três repositórios.

| Requisito | Status | Bloqueia |
|---|---|---|
| Exclusão de conta no app | ❌ | 🔒 Rejeição certa |
| Exclusão de conta por URL web | ❌ | 🔒 Rejeição certa |
| Política de privacidade publicada | ❌ | 🔒 Rejeição certa |
| Formulário Data Safety | ❌ | 🔒 Rejeição certa |
| Declaração de Advertising ID | ❌ | 🔒 Rejeição certa |
| Termos de uso | ❌ | 🔒 |
| Classificação indicativa | ❌ | 🔒 |
| Ficha da loja: descrição, capturas, ícone | ❌ | 🔒 |
| Conta de desenvolvedor verificada | ⚠️ | Não verificável; prazo externo |
| SKUs criados na Play Console | ⚠️ | Não verificável; 8 produtos esperados |
| Conta de pagamento | ⚠️ | Não verificável |
| Conta AdMob de produção | ⚠️ | Não verificável; ID de teste em uso |
| `assetlinks.json` no domínio | ❌ | Deep links falham |
| LGPD — canal do titular | ❌ | 🔒 Exposição legal |
| LGPD — base legal e consentimento | ❌ | 🔒 |
| LGPD — encarregado (DPO) | ❌ | 🔒 |
| LGPD — política de retenção | ❌ | 🔒 |

---

## 8. Contagem consolidada

| Categoria | ✅ | 🟡 | ⚠️ | ❌ | Bloqueadores 🔒 |
|---|---|---|---|---|---|
| Android — jornada | 18 | 1 | 2 | 3 | 1 |
| Android — monetização | 7 | 0 | 2 | 0 | 1 |
| Android — plataforma | 2 | 2 | 3 | 7 | 4 |
| Backend — domínios | 7 | 3 | 3 | 0 | 0 |
| Backend — plataforma | 12 | 0 | 0 | 14 | 8 |
| Studio | 16 | 0 | 1 | 5 | 1 |
| Conformidade | 0 | 0 | 4 | 13 | 13 |
| **Total** | **62** | **6** | **15** | **42** | **28** |

---

## 9. Leitura da matriz

**O produto está entregue. A plataforma e a conformidade não.**

Olhando só as capacidades voltadas ao usuário e ao operador — jornada Android, domínios do backend, telas do
Studio — a taxa de entrega é **41 de 47 itens**, com as seis exceções sendo homologação pendente ou cobertura
de teste ausente, não funcionalidade faltando.

Olhando plataforma e conformidade, a proporção inverte: **14 de 41 itens entregues**, e 25 dos 28
bloqueadores de lançamento estão concentrados nessas duas categorias.

Isso desenha o problema com precisão e também aponta a solução. **Não há produto a construir.** Há um
ambiente a criar, uma conformidade a produzir e uma operação a montar — três trabalhos que não competem
entre si, que podem correr em paralelo, e nenhum dos quais depende de escrever mais funcionalidade.

Vale registrar o corolário para as decisões de escopo que virão sob pressão de prazo: **cortar funcionalidade
não antecipa o lançamento**, porque não é funcionalidade que está no caminho crítico.
