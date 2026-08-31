# 07 — Bootstrap em Máquina Limpa

Entregue no épico **RC1-A**. Antes deste épico, nenhuma das três frentes compilava
localmente e toda verificação dependia do GitHub Actions — a causa dos 176 arquivos
`RC2_E06_*.md` de churn de CI.

---

## Pré-requisitos

| Ferramenta | Versão | Frentes |
|---|---|---|
| JDK | 21 (backend) e 17 (Android) | backend, Android |
| Android SDK | platform 36, build-tools 36.0.0 | Android |
| Node.js | ≥ 22 | Studio |
| Docker + Compose | qualquer recente | backend (banco local) |

**Não é preciso instalar Maven nem Gradle.** Os wrappers (`./mvnw`, `./gradlew`)
baixam a versão correta na primeira execução e estão versionados justamente para
que a build local seja idêntica à do CI.

---

## Backend

```bash
cd ideiasboxsa-brasil-drama-backend

# Banco local
docker compose up -d postgres

# Testes — baixa Maven 3.9.9 na primeira execução
./mvnw -q test

# Subir a aplicação
JWT_SECRET=um-segredo-local-com-pelo-menos-32-bytes \
  ./mvnw spring-boot:run
```

> `JWT_SECRET` é **obrigatório**. O valor padrão embutido foi removido no épico
> RC1-A: um deploy sem a variável subia com uma chave de assinatura publicada no
> repositório, o que permitia forjar token de qualquer usuário. A aplicação agora
> falha na inicialização com `JWT_SECRET_REQUIRED`.

Alternativa com tudo em contêiner (o compose já define um segredo local):

```bash
docker compose up --build
```

Verificação:

```bash
curl -s localhost:8080/actuator/health   # {"status":"UP"}
curl -s localhost:8080/v1/home | head -c 200
```

---

## Android

```bash
cd brasil-drama-android

# Testes unitários do flavor DEV
./gradlew testDevDebugUnitTest

# Compilar testes de instrumentação (sem aparelho)
./gradlew compileDevDebugAndroidTestKotlin

# APK DEV debug
./gradlew assembleDevDebug
# -> app/build/outputs/apk/dev/debug/app-dev-debug.apk
```

Se o SDK não estiver no lugar padrão, crie `local.properties` (já ignorado pelo Git):

```properties
sdk.dir=/caminho/para/Android/Sdk
```

### Chave de debug

O CI usa uma chave de debug **estável** para manter válido o SHA-1 registrado no
Firebase/Google Sign-In. Localmente o Gradle gera uma chave própria — o app compila
e roda, mas o login com Google não funciona. Para reproduzir o comportamento do CI,
ver `ci/README.md`.

---

## Studio

```bash
cd brasil-drama-admin

npm ci                                    # usa package-lock.json
npm run typecheck
NEXT_PUBLIC_API_BASE_URL=https://api-drama-dev.ideiasbox.com/ npm run build
npm run dev                               # http://localhost:3000
```

> Use `npm ci`, não `npm install`. O lockfile foi introduzido no épico RC1-A: antes
> dele cada build resolvia dependências de novo e duas builds do mesmo commit podiam
> produzir artefatos diferentes.

---

## Verificação de que o épico RC1-A fechou

```bash
# Wrappers presentes e versionados
test -x brasil-drama-android/gradlew            && echo "ok gradlew"
test -f brasil-drama-android/gradle/wrapper/gradle-wrapper.jar && echo "ok gradle-wrapper.jar"
test -x ideiasboxsa-brasil-drama-backend/mvnw   && echo "ok mvnw"
test -f brasil-drama-admin/package-lock.json    && echo "ok lockfile"

# Nenhum artefato de build ou keystore rastreado no Android
git -C brasil-drama-android ls-files | grep -E '(^|/)build/|\.apk$|\.jks$' && echo "FALHA" || echo "ok: sem artefato"

# O wrapper não pode estar sendo ignorado
git -C brasil-drama-android check-ignore -q gradle/wrapper/gradle-wrapper.jar \
  && echo "FALHA: wrapper ignorado" || echo "ok: wrapper versionado"
```

---

## O que ainda não fecha localmente

| Item | Motivo | Onde roda |
|---|---|---|
| Testes de instrumentação em aparelho | Exige emulador com KVM | Mobile Center (épico RC1-G) |
| Build de release / AAB | `signingConfig` ainda não existe | Épico RC1-F |
| Deploy | Não há ambiente de produção | Épico RC1-F |
