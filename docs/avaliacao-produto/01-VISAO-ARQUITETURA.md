# 01 — Visão de Arquitetura

Data: 2026-08-31 · Perspectiva: Arquiteto de Software

---

## 1. Desenho atual

```
                    ┌──────────────────────────────┐
                    │   Google Play  ·  AdMob      │
                    │   Firebase FCM  ·  Mailgun   │
                    └───────┬──────────────┬───────┘
                            │              │
   ┌────────────────┐       │              │      ┌──────────────────────┐
   │ Android app    │───────┘              └──────│ Brasil Drama Studio  │
   │ Kotlin/Compose │                             │ Next.js 15 (estático)│
   │ 11.4k linhas   │                             │ 4.9k linhas          │
   └───────┬────────┘                             └──────────┬───────────┘
           │ 59 endpoints /v1/**                             │ /v1/admin/**
           │ JWT · X-Visitor-Id                              │ JWT ADMIN
           └──────────────────┬──────────────────────────────┘
                              ▼
              ┌───────────────────────────────────┐
              │  Backend Spring Boot 3.3.13       │
              │  Java 21 · 7.9k linhas · monólito │
              │  44 @RestController · 136 rotas   │
              │  17 pacotes de domínio            │
              └────────┬──────────────────┬───────┘
                       ▼                  ▼
              ┌────────────────┐  ┌──────────────────┐
              │ PostgreSQL 16  │  │ S3 privado       │
              │ 36 changelogs  │  │ presign+multipart│
              │ Liquibase      │  │ dev-media        │
              └────────────────┘  └──────────────────┘
```

**Veredito arquitetural: o desenho está certo.** Monólito modular em Spring Boot, banco relacional único,
mídia fora do banco, autoridade no servidor, admin como produto separado. Para um produto de microdramas
nesta fase, essa é a escolha correta — e é a escolha que a maioria dos times erra, indo cedo demais para
microsserviços. Não mexer.

Os problemas de arquitetura são localizados, não estruturais. Estão abaixo.

---

## 2. Modularidade do backend

17 pacotes de domínio: `admin`, `analytics`, `auth`, `brand`, `catalog`, `config`, `home`, `identity`,
`library`, `media`, `monetization`, `push`, `recommendation`, `rewards`, `wallet`.

A organização é **por domínio, não por camada** — decisão correta e consistentemente aplicada. Cada arquivo
`*Api.java` reúne entidade, repositório, serviço e controller do mesmo assunto no mesmo arquivo, com
visibilidade de pacote. É um estilo denso mas coerente: `GooglePurchaseApi.java` tem 385 linhas e contém a
entidade, o verificador Google, o serviço e o controller, sem nada vazando para fora do pacote.

**Isso funciona bem hoje e vai doer quando o time crescer.** Arquivos de 300–400 linhas com quatro
responsabilidades são navegáveis para quem escreveu e opacos para quem chega. Não é dívida urgente,
mas é o primeiro lugar onde o onboarding de um novo dev vai travar.

### Pacotes sem teste

| Pacote | Testes | Risco |
|---|---|---|
| `media` | ❌ nenhum | Presign S3 e multipart — pipeline de vídeo inteiro |
| `identity` | ❌ nenhum | Fusão visitante→conta: perde histórico e curtidas se errar |
| `push` | ❌ nenhum | Dispatcher FCM |
| `recommendation` | ❌ nenhum | 4 endpoints de descoberta |
| `home` | ❌ nenhum | Curadoria e trilhos da Home |

`MediaStorageService` (137 linhas) e `VisitorMergeService` são os dois pontos mais delicados do sistema sem
rede de proteção. O primeiro governa todo o pipeline de mídia; o segundo executa SQL de migração de dados
com `delete from drama_like where visitor_id=?` (`VisitorMergeService.java:59`) — um bug ali apaga curtidas
de usuário sem recuperação.

---

## 3. O problema arquitetural nº 1 — o contrato Android↔Backend não é um contrato

O README do backend declara: *"A API deve permanecer compatível com o Retrofit definido em
`BrasilDramaApi.kt`."*

Isso é uma convenção social, não um contrato. Mede-se assim:

| | |
|---|---|
| Endpoints consumidos pelo app | 59 (`BrasilDramaApi.kt` 51, `RecommendationApi.kt` 4, `PushDeviceApi.kt` 2, `RewardedEpisodeApi.kt` 2) |
| Mapeamentos expostos pelo backend | 136 |
| Verificação automatizada da compatibilidade | **nenhuma** |
| Repositórios envolvidos | 2, sem commit atômico entre eles |

**Por que isto é grave e não apenas feio:** os três repositórios são independentes
(`git -C <front>` obrigatório, sem monorepo). Uma mudança de campo no backend é um commit; a adaptação no
Android é outro. Nada força que aconteçam juntos. E o Android, uma vez publicado como APK assinado na loja,
**não pode ser corrigido em lockstep com o servidor** — o usuário tem que atualizar.

O modo de falha é concreto: alguém renomeia `videoUrl` para `mediaUrl`, o CI do backend fica verde
(nenhum teste do backend conhece o DTO do app), o CI do Android fica verde (nenhum teste do app fala com o
backend real), o deploy sobe, e o player para de funcionar em todos os aparelhos instalados.

**Recomendação (M7 no plano existente, elevo a prioridade):** o backend publica um OpenAPI no CI; o Android
valida seus DTOs contra esse arquivo em um teste. Transforma em falha de build o que hoje é incidente de
produção. Custo: 1 a 2 dias. É a melhor relação custo/risco disponível no projeto inteiro.

---

## 4. O problema arquitetural nº 2 — regra de negócio no cliente

O limiar de maratona é `3`, e existe **só** no app:

```kotlin
// app/src/main/java/br/com/brasildrama/app/player/BingeSessionTracker.kt:4
class BingeSessionTracker(private val bingeThreshold: Int = 3)
```

Não há configuração equivalente no backend. O Studio não reconstrói o limiar. Portanto **a definição de
"maratona" só muda com uma nova submissão à Google Play.**

Isso viola dois contratos que o próprio projeto escreveu:
- `RC2_SHORT_DRAMA_UX_CONTRACT.md` — "não hardcodar regras econômicas/recomendação na UI"
- `RC2_PRODUCT_EXPERIENCE_CONTRACT.md` — "superfícies server-driven"

E viola por um motivo específico que importa para o negócio: maratona é uma **métrica de produto que se
pretende observar e calibrar**. O Studio tem um dashboard inteiro em `/analytics/binge` para acompanhá-la.
Observar uma métrica cuja definição você não pode ajustar sem esperar a revisão da loja é observar sem poder agir.

**Recomendação:** um endpoint `/v1/config/experience` devolvendo parâmetros de sinal, com fallback local
para os valores atuais. Meio dia de trabalho. Deve entrar **antes** do lançamento, não depois — depois do
lançamento o custo de mudar vira um ciclo de loja.

---

## 5. O problema arquitetural nº 3 — não existe camada de produção

O sistema tem **um** ambiente desenhado: DEV.

| Artefato | dev | hml | prod |
|---|---|---|---|
| Profile Spring | ✅ `application-dev-cloud.yml` | ❌ | ❌ |
| Flavor Android | ✅ completo | ⚠️ URL sem infraestrutura | ⚠️ URL sem infraestrutura |
| Firebase | ✅ configurado | ❌ strings vazias | ❌ strings vazias |
| Deploy do Studio | ✅ `deploy-dev.yml` | ❌ | ❌ |
| Deploy do backend | ⚠️ só publica imagem | ❌ | ❌ |

Os flavors `hml` e `prod` apontam para `hml-api.brasildrama.com.br` e `api.brasildrama.com.br`
(`app/build.gradle.kts`), domínios para os quais não há profile, não há deploy e não há infraestrutura
descrita em nenhum repositório.

O `docker-publish.yml` marca a imagem como `latest` em push para `main` — mas nada consome essa tag.
Não há workflow de deploy do backend para lugar nenhum. O deploy DEV atual é manual ou externo ao repositório
(não verificável a partir do código).

**Isto é o item de maior prazo do projeto.** Não é difícil, é demorado: VPC, RDS, ECS/EKS ou equivalente,
certificados, DNS, secret manager, backup, e um ensaio de deploy antes do real.

---

## 6. Fronteiras de segurança e autorização

`SecurityConfig.java:25-34` é curto o bastante para ser lido inteiro e auditado — isso é uma qualidade, não um defeito.

```java
.requestMatchers("/v1/auth/**", "/v1/catalog/**", "/v1/home", "/v1/monetization/catalog").permitAll()
.requestMatchers("/v1/recommendations/**").permitAll()
.requestMatchers("/v1/analytics/playback/events", "/v1/analytics/playback/visitor", "/v1/continue-watching").permitAll()
.requestMatchers("/v1/rewards/ads/ssv", "/v1/rewards/guest-overview").permitAll()
.requestMatchers("/v1/admin/auth/**").permitAll()
.requestMatchers("/v1/admin/**").hasRole("ADMIN")
.anyRequest().authenticated()
```

**Correto:** `anyRequest().authenticated()` como padrão fail-closed; admin isolado por role; stateless.

**Pontos de atenção arquitetural:**

1. **Nenhum controle de taxa em endpoint público algum.** `/v1/auth/**` está aberto para força bruta e
   preenchimento de credenciais. `/v1/analytics/playback/events` aceita ingestão anônima ilimitada — qualquer
   um pode inflar as métricas do produto ou encher a tabela `playback_event`. Não há `bucket4j`, filtro de
   throttling, nem WAF descrito.

2. **Nenhuma configuração de CORS no código.** O Studio é uma SPA estática servida de
   `studio-drama-dev.ideiasbox.com` chamando `api-drama-dev.ideiasbox.com` — origem cruzada. Sem CORS no
   Spring, o navegador bloqueia. Como o Studio aparentemente funciona, o CORS deve estar sendo resolvido em
   camada de infraestrutura (ALB, CloudFront). **Isso é uma dependência oculta e não documentada:** a política
   de origem do sistema vive fora do controle de versão.

3. **Segredo JWT com valor padrão embutido.**
   ```java
   // JwtService.java:19
   @Value("${security.jwt.secret:${JWT_SECRET:dev-only-change-this-secret-32-bytes}}")
   ```
   O profile `dev-cloud` exige `${JWT_SECRET}` sem default, então falha rápido — bom. Mas o profile base não,
   e o default tem 36 bytes, passando na validação de comprimento. Um deploy sem o profile correto sobe com
   **chave de assinatura pública e conhecida**: qualquer pessoa forja token de qualquer usuário. O tipo de
   erro que só acontece uma vez, às três da manhã, num deploy de emergência.

---

## 7. Arquitetura de dados

36 changelogs Liquibase, `ddl-auto: validate`, contextos `base`/`dev` corretamente separados
(`db.changelog-master.yaml:108` marca o seed como `context: dev`). Disciplina de migração acima da média.

**Observações:**

- O seed DEV insere URLs de vídeo de amostra do Google (`db.changelog-master.yaml:133,143,153`). Está
  corretamente isolado em contexto `dev` e não vai para produção — mas é a causa raiz do bloqueador B1
  do plano RC1: o ambiente não consegue exercitar o pipeline de vídeo próprio.
- **Nenhuma estratégia de backup ou retenção descrita** em nenhum repositório.
- **Nenhum plano de migração de dados ou rollback.** Liquibase suporta `rollback` e nenhum changeset o define.
  Uma migração ruim em produção não tem caminho de volta automatizado.
- `playback_event` recebe ingestão anônima e ilimitada, sem particionamento nem política de retenção. É a
  tabela que vai crescer mais rápido e a que menos atenção recebeu.

---

## 8. Arquitetura do Studio

Next.js 15 com `output: "export"` — exportação estática pura, servida de S3 + CloudFront. Sem servidor Node.

**Consequências dessa escolha, que são deliberadas e majoritariamente boas:**
- Sem superfície de ataque server-side, custo de hospedagem quase nulo, deploy trivial.
- Mas: **o token administrativo vive no `sessionStorage` do navegador** (`login/page.tsx:21`), acessível a
  qualquer XSS. Não há como usar cookie `HttpOnly` sem um servidor.
- Não há como definir cabeçalhos de segurança (CSP, HSTS, `X-Frame-Options`) a partir da aplicação — teriam
  que vir do CloudFront, e não há configuração de CloudFront versionada em nenhum repositório.

Para um painel interno acessado por poucos operadores, `sessionStorage` é um risco aceitável **se** houver
CSP. Hoje não há nem uma coisa nem outra verificável.

Um detalhe de qualidade que merece nota: o `deploy-dev.yml` do Studio valida rota por rota após o deploy,
incluindo os assets JS referenciados por cada página, e falha se qualquer um retornar diferente de 200.
É o pipeline mais rigoroso das três frentes — e é o da frente sem nenhum teste unitário. Vale copiar o rigor
para as outras duas.

---

## 9. Resumo arquitetural

| Dimensão | Nota | Comentário |
|---|---|---|
| Escolha de stack | 🟢 Boa | Adequada ao problema e ao tamanho do time |
| Modularidade | 🟢 Boa | Por domínio, consistente; arquivos densos demais a prazo |
| Fronteira de autorização | 🟢 Boa | Simples, auditável, fail-closed |
| Camada de dados | 🟡 Aceitável | Migrações disciplinadas; sem backup, rollback ou retenção |
| Contrato entre frentes | 🔴 Frágil | Convenção em README, zero verificação, 59 endpoints em risco |
| Configuração server-driven | 🔴 Ausente | Regra econômica presa no APK |
| Camada de produção | 🔴 Inexistente | Um ambiente desenhado; dois flavors apontando para o vazio |
| Defesa de perímetro | 🔴 Ausente | Sem rate limit, CORS fora do código, JWT com default perigoso |

**Conclusão do arquiteto:** a fundação está correta e não precisa ser refeita. O que falta não é redesenho —
é fechar três lacunas específicas (contrato verificável, configuração server-driven, camada de produção) e
uma transversal (defesa de perímetro). Nenhuma delas exige mudar uma decisão estrutural já tomada.
