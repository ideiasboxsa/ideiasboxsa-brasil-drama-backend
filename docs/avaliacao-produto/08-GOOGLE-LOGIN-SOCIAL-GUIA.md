# 08 — Login Social com Google: o que falta e como fazer

Data: 2026-08-31 · Épico RC1-B (extensão)

---

## Correção ao dossiê

No [documento 05](05-MATRIZ-ENTREGAS-E-GAPS.md) marquei **"Entrar com Google ✅"** e
**"Recuperação de senha ✅"**. Estava errado.

Contei endpoints na interface Retrofit e no `build.gradle.kts` sem verificar se o
backend os implementava. Verificado agora contra o DEV em execução:

```
POST /v1/auth/google           → HTTP 501  Not Implemented
POST /v1/auth/password/forgot  → HTTP 501  Not Implemented
POST /v1/auth/password/reset   → HTTP 501  Not Implemented
```

Os três eram stubs `throw new ResponseStatusException(NOT_IMPLEMENTED, ...)`.
**Login social e recuperação de senha nunca funcionaram, em ambiente nenhum.**

A matriz de entregas foi corrigida. Recuperação de senha continua pendente —
não estava no escopo deste épico.

---

## Diagnóstico: estava quebrado nos dois lados

| Camada | Estado antes | Estado agora |
|---|---|---|
| App: obter ID token | ✅ `GoogleSignInManager` com Credential Manager | inalterado |
| App: `GOOGLE_WEB_CLIENT_ID` | ❌ string vazia — CI nunca passou o valor | ⚠️ lê o Secret; **falta o Secret** |
| Backend: `/v1/auth/google` | ❌ stub 501 | ✅ implementado |
| Backend: validar ID token | ❌ inexistente | ✅ `GoogleIdentityVerifier` |
| Backend: vincular conta | ❌ inexistente | ✅ por `sub`, depois por e-mail |
| GCP: OAuth Client ID | ❌ não existe | 🔴 **você precisa criar** |
| AWS SSM: client ID | ❌ não existe | 🔴 **você precisa criar** |

Mesmo com o botão na tela, o app retornava `GOOGLE_CLIENT_ID_NOT_CONFIGURED`
antes de abrir qualquer diálogo, porque `serverClientId` chegava vazio.

---

## O que verifiquei na AWS

```
Conta ........ 369598751783  (usuário ideiasbox-devops)
Backend DEV .. EC2 i-0bcbbaa31d8548a97 · 50.17.219.75 · api-drama-dev.ideiasbox.com
Buckets ...... brasil-drama-dev-media · brasil-drama-dev-admin-web
```

Parâmetros existentes em `/brasil-drama/dev/`:

| Parâmetro | Tipo | Serve a |
|---|---|---|
| `admin/bootstrap_email` · `_password` · `_enabled` · `_display_name` | String / SecureString | Bootstrap do Studio |
| `backend/jwt_secret` | SecureString | Assinatura de JWT |
| `database/password` | SecureString | RDS |
| `firebase/service_account` | SecureString | **FCM push** — projeto `brasil-drama` |
| `github/pat` | SecureString | CI |
| `mailgun/api_key` · `smtp_password` | SecureString | E-mail administrativo |

**Ausentes, e é por isso que nada do Google funciona:**

| Parâmetro que falta | Consequência hoje |
|---|---|
| `/brasil-drama/dev/google/web_client_id` | Login social: 501 no backend, botão morto no app |
| `/brasil-drama/dev/google/play_service_account` | **Verificação de compra devolve 503** — nenhuma compra liquida em DEV |

> O segundo é um achado novo e sério: `GooglePlayVerifier.isConfigured()` lê
> `google.play.service-account-json-base64`, que não tem valor em lugar nenhum.
> Toda a monetização está inoperante em DEV, não só o login.

O Secrets Manager tem apenas segredos de `bussola-encanto`, outro projeto — nada
de Brasil Drama.

---

## Dado que você vai precisar: SHA-1 da chave de DEV

Extraí do keystore versionado (`ci/brasil-drama-dev-debug.keystore.b64`):

```
Subject : C=BR, O=Ideias Box, CN=Brasil Drama DEV
Validade: 2026-08-24 → 2054-01-09

SHA-1   : E4:11:4E:93:10:76:3A:7C:58:87:5A:EC:F6:09:78:7F:04:40:B0:72
SHA-256 : BC:6C:70:FE:00:76:E0:7E:A4:BE:9F:73:00:57:46:14:10:E5:74:7C:48:91:D7:C8:60:63:49:6E:76:F9:D5:F8
```

É esta a chave que o CI usa para assinar o APK DEV, então é este SHA-1 que o
Google precisa conhecer.

---

## A tela do Firebase Authentication: o que preencher

Consultado em 2026-08-31 pela Firebase Management API, com o service account do SSM:

```
projectId       : brasil-drama
projectNumber   : 578932710818
Apps Android    : 1 → "Brasil Drama Android DEV" (br.com.brasildrama.app.dev)
SHA cadastrados : NENHUM   (resposta {} do endpoint /sha)
hml e prod      : não registrados
```

### A resposta curta: você não precisa dessa tela

**O app não usa Firebase Authentication.** A dependência é só
`com.google.firebase:firebase-messaging` — não há `firebase-auth`. O fluxo é
Credential Manager → ID token → seu backend → seu próprio JWT. Firebase Auth é um
produto paralelo que este app não consome.

**Mas ela serve de atalho legítimo:** ativar o provedor Google ali faz o Firebase
criar automaticamente os OAuth clients no projeto GCP — que é exatamente o que
falta. Menos cliques que criar à mão.

### Campo a campo

| Campo na tela | O que fazer |
|---|---|
| *"É preciso fornecer a impressão digital SHA-1... Configurações do projeto > Seus apps"* | **Único item obrigatório.** Hoje há zero SHA cadastrado. Adicione o SHA-1 abaixo no app DEV |
| *"Adicionar IDs de cliente à lista de permissões usando projetos externos (opcional)"* | **Deixe vazio.** Serve para aceitar tokens emitidos a clients de *outro* projeto GCP. Não é o caso |
| *"ID do cliente da Web"* | **Preenche sozinho ao ativar.** É este valor que você copia — é o `GOOGLE_WEB_CLIENT_ID` |
| *"Chave secreta do cliente da Web"* | **Ignore. Nunca coloque no app.** Serve para troca de authorization code server-side, fluxo que não usamos. Vazar isso em APK é incidente de segurança |

### Ordem correta

1. **Configurações do projeto › Seus apps › Brasil Drama Android DEV › Adicionar
   impressão digital** →
   `E4:11:4E:93:10:76:3A:7C:58:87:5A:EC:F6:09:78:7F:04:40:B0:72`
2. Voltar em **Authentication › Sign-in method › Google › Ativar**
3. Copiar o **ID do cliente da Web** que aparecer
4. Seguir para a seção *"Depois de obter os valores"* abaixo

Registrar o SHA-1 no Firebase cria, no projeto GCP por trás, o OAuth client do
tipo Android para `br.com.brasildrama.app.dev`. Sem ele o diálogo do Google abre
e fecha sem devolver token.

> **hml e prod não existem no Firebase.** Só o app DEV está registrado, o que
> confirma o achado do dossiê de que os flavors `hml` e `prod` têm configuração
> Firebase vazia. Cada um precisa do seu próprio app registrado, com o SHA-1
> correspondente — para prod, o de release, que ainda não existe (épico RC1-F).

---

## O que só você pode fazer

Não tenho acesso ao Google Cloud Console nem ao Play Console. Os passos abaixo
são todos no navegador.

### Passo 1 — Tela de consentimento OAuth

`console.cloud.google.com` → projeto **`brasil-drama`** (o mesmo do Firebase, já
confirmado pelo service account) → **APIs e serviços › Tela de permissão OAuth**.

- Tipo: **Externo**
- Nome do app: `Brasil Drama`
- E-mail de suporte e de contato do desenvolvedor
- Escopos: apenas `email`, `profile`, `openid` — não peça mais nada, escopo extra
  aciona verificação da Google e adiciona semanas
- Enquanto estiver em **Teste**, só contas na lista de testadores conseguem
  entrar. Adicione as suas para homologar.

> Publicar a tela de consentimento é pré-requisito do lançamento, mas com esses
> três escopos básicos não exige revisão. Deixe em Teste até o RC.

### Passo 2 — Client ID **Web** (o mais importante)

**Credenciais › Criar credenciais › ID do cliente OAuth › Aplicativo da Web**

- Nome: `Brasil Drama Backend`
- Não precisa de URI de redirecionamento — o fluxo é por ID token, não por código

Copie o **Client ID** (`...apps.googleusercontent.com`).

> Este é o valor que confunde todo mundo: **o app Android usa o client ID *Web*,
> não o Android.** O `serverClientId` do `GetSignInWithGoogleOption` diz ao Google
> "emita um token cuja audiência é o meu backend". É o mesmo valor nos dois lados
> — app e servidor — e é o que o `GoogleIdentityVerifier` valida como `aud`.

### Passo 3 — Client ID **Android** (um por flavor)

Não gera valor para copiar, mas **sem ele o Google recusa a requisição do app**.

**Criar credenciais › ID do cliente OAuth › Android**, três vezes:

| Nome | Nome do pacote | SHA-1 |
|---|---|---|
| `Brasil Drama DEV` | `br.com.brasildrama.app.dev` | `E4:11:4E:93:10:76:3A:7C:58:87:5A:EC:F6:09:78:7F:04:40:B0:72` |
| `Brasil Drama HML` | `br.com.brasildrama.app.hml` | mesmo SHA-1 (mesma chave de debug) |
| `Brasil Drama PROD` | `br.com.brasildrama.app` | SHA-1 da chave de release — **ainda não existe**, épico RC1-F |

Com Play App Signing, o PROD precisa do SHA-1 **de upload** e também do **de
assinatura do app** que a Google gera. Os dois ficam em
*Play Console › Configuração › Integridade do app*. Cadastre ambos.

### Passo 4 — Service account da Play (destrava a monetização)

Separado do Firebase, e é o que está fazendo a compra devolver 503.

1. **Google Play Console › Configuração › Acesso à API** → vincular ao projeto
   GCP `brasil-drama`
2. Criar service account, conceder **Ver dados financeiros** e **Gerenciar pedidos
   e assinaturas**
3. Baixar a chave JSON

---

## Depois de obter os valores: o que rodar

Substitua os `<...>` pelos valores reais.

```bash
export AWS_REGION=us-east-1

# Client ID Web — login social
aws ssm put-parameter \
  --name /brasil-drama/dev/google/web_client_id \
  --type String \
  --value '<CLIENT_ID>.apps.googleusercontent.com' \
  --description 'OAuth Web client ID: audiencia do ID token do Sign in with Google'

# Service account da Play — verificação de compra
aws ssm put-parameter \
  --name /brasil-drama/dev/google/play_service_account \
  --type SecureString \
  --value "$(base64 -w0 play-service-account.json)" \
  --description 'Service account com acesso a Android Publisher API, em base64'

shred -u play-service-account.json   # não deixar a chave em disco
```

GitHub Secret, para o APK sair com o client ID embutido:

```bash
gh secret set GOOGLE_WEB_CLIENT_ID --repo <org>/brasil-drama-android \
  --body '<CLIENT_ID>.apps.googleusercontent.com'
```

Variáveis no EC2 do backend (`brasil-drama-dev-backend`), lidas pelo profile
`dev-cloud`:

```bash
GOOGLE_WEB_CLIENT_ID=<CLIENT_ID>.apps.googleusercontent.com
GOOGLE_PLAY_SERVICE_ACCOUNT_B64=<conteudo base64>
GOOGLE_PLAY_PACKAGE_NAME=br.com.brasildrama.app
```

> O client ID **não é segredo** — vai embutido no APK e é público por desenho.
> Está como `String` no SSM de propósito. A chave da Play **é** segredo:
> `SecureString`, sempre.

---

## Como validar que funcionou

```bash
API=https://api-drama-dev.ideiasbox.com

# Antes: 501. Depois de configurado, um token falso deve dar 401 —
# ou seja, o endpoint existe e está validando de verdade.
curl -s -o /dev/null -w '%{http_code}\n' -X POST $API/v1/auth/google \
  -H 'Content-Type: application/json' -d '{"idToken":"invalido"}'
```

| Resposta | Significado |
|---|---|
| `501` | `GOOGLE_WEB_CLIENT_ID` ainda ausente no backend |
| `401` | ✅ Configurado e validando — token falso rejeitado, como deve ser |
| `200` | 🔴 Grave: aceitou token inválido. Parar e investigar |

No aparelho, com o APK gerado após o Secret existir: o botão deve abrir o seletor
de contas do Google. Se ainda falhar com `GOOGLE_CLIENT_ID_NOT_CONFIGURED`, o APK
foi construído antes do Secret.

Erros comuns e o que significam:

| Erro no app | Causa provável |
|---|---|
| `GOOGLE_CLIENT_ID_NOT_CONFIGURED` | Secret ausente no build |
| Diálogo abre e fecha sem retorno | Client ID **Android** faltando, ou SHA-1 errado |
| `GOOGLE_TOKEN_INVALID` no backend | Audiência divergente: app e backend com client IDs diferentes |
| `GOOGLE_EMAIL_NOT_VERIFIED` | Conta Google sem e-mail verificado — comportamento correto |

---

## O que implementei neste épico

**Backend**

- `GoogleIdentityVerifier` — validação criptográfica do ID token contra as chaves
  públicas do Google, via `TokenVerifier` do google-auth-library (já era
  dependência do projeto; nenhuma nova foi adicionada). Verifica assinatura,
  emissor, audiência e expiração.
- `/v1/auth/google` implementado, com merge de visitante e bônus de boas-vindas,
  igual aos outros caminhos de entrada.
- Migração `038`: coluna `google_subject` única em `app_user`.
- `GoogleAuthContractTest` — quatro cenários de vinculação de conta.

**Android**

- `GOOGLE_WEB_CLIENT_ID` passa a ser injetado nos dois workflows via Secret.
- Passo de verificação que avisa no log quando o login social sai desativado, em
  vez de o defeito aparecer só no aparelho.

### Decisão de vinculação, e por que ela importa

A busca é **por `sub` do Google primeiro, e só depois por e-mail**:

- **Por que `sub` antes:** é estável. Quem trocar o e-mail na conta Google
  continua entrando na mesma conta do Brasil Drama, em vez de ganhar uma nova e
  perder carteira, histórico e direitos comprados.
- **Por que e-mail depois:** quem já tinha conta com senha e resolve entrar pelo
  Google precisa cair na conta existente. Sem isso, a inserção colidiria na
  unicidade do e-mail e o login social falharia para todo usuário antigo.
- **Por que é seguro:** o verificador recusa token sem `email_verified`. Sem essa
  checagem, vincular por e-mail seria tomada de conta — bastaria um token com
  e-mail alheio não verificado para assumir a conta existente.

Adotar a conta **não** apaga a senha: quem tinha os dois métodos continua com os
dois. Contas criadas pelo Google nascem com `passwordHash` nulo, e `login()` já
trata nulo como credencial inválida — não há caminho de entrada aberto.
