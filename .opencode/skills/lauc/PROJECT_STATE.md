# Estado de continuidade — Frey

## Regras
Este arquivo é um resumo de trabalho, não prova do estado atual do código.
Confirme arquivos e Git antes de cada tarefa.
Mantenha este registro curto; remova detalhes antigos que já não ajudam.

## Contexto conhecido
- Projeto Android SimpleLauncher, com nome visível planejado Frey.
- Package ID: `com.simples.launcher`; preservar.
- Repositório: `freyymotion/laucherfrey`.
- Build via GitHub Actions pode ser necessário se o Android SDK não estiver disponível no Termux.
- Nunca alterar `.review-backup/`.
- Sem commit, push, tag ou release sem autorização explícita.

## Alterações anteriormente relatadas
- Permissão relacionada à desinstalação de pacotes e ajustes no menu de pressão longa.
- Ajustes de transparência da gaveta de aplicativos.
- Seção de desenvolvedor com identidade e link.
- Build do GitHub Actions já foi concluído com sucesso em uma versão anterior.

Esses pontos são históricos: confira o código e os commits atuais antes de concluir que continuam presentes ou funcionando.

## Planejamento
1. [feito] Nome visível FREY, atalho LAUNCHER via activity-alias e textos de sistema.
2. [feito] Primeira abertura com convite ao launcher padrão pelo fluxo oficial (RoleManager/ajustes).
3. [feito] Seção do desenvolvedor: FREYY, Instagram @jaofrey_ e convite para mensagem.
4. [feito] Modo desenvolvedor (7 toques na versão) com diagnósticos sem dados sensíveis.
5. [base feita] Atualizador GitHub Releases: constantes e comparação de versão em `Updates.kt`, sem rede.
6. Próximo: verificação real de releases (requer INTERNET) e testes em aparelho.
7. Wallpaper animado otimizado permanece como ideia futura, não autorizado.

## Última tarefa
Preparação da assinatura segura de release e do build de distribuição.

- `app/build.gradle`: signingConfig `release` criado somente se existirem
  `RELEASE_KEYSTORE_FILE`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS` e
  `RELEASE_KEY_PASSWORD` e o arquivo existir. Sem credenciais, `assembleRelease`
  gera APK não assinado e `assembleDebug` continua funcionando.
- `.github/workflows/build.yml`: debug sempre é compilado/upload. Com
  `RELEASE_KEYSTORE_BASE64` presente, decodifica a keystore temporariamente,
  compila `assembleRelease`, verifica com `apksigner --print-certs`, envia o
  artefato `app-release` e só em tag `v*` publica o APK assinado. Publicação de
  APK debug como release foi removida. Tags com hífen (ex.: `v1.0.0-beta.1`)
  são marcadas como pré-lançamento; tags sem hífen continuam sendo estáveis.
- `.gitignore`: ignora `*.jks`, `*.keystore`, `keystore.properties` e
  `frey-release.jks.b64`. Nenhum segredo foi adicionado ao repositório.
- `applicationId` permanece `com.simples.launcher`; atualizador não foi alterado.
- Commit local preparado com os quatro arquivos autorizados; push, secrets
  restantes e workflow dependem de confirmação.
- Versão atual: `versionCode 1` / `versionName "1.0"`. Proposta não aplicada:
  `versionName "1.0.0-beta.1"` com `versionCode 2`.

## Secrets necessários no GitHub
`RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`,
`RELEASE_KEY_PASSWORD`.

## Risco de compatibilidade
Releases anteriores, se instaladas a partir do APK debug, usam assinatura debug.
A primeira versão assinada com a chave permanente exigirá desinstalação nessas
instalações (perda de dados), pois o Android não aceita troca de assinatura.
Não há tags/releases registradas no estado anterior; confirmar antes do primeiro release.

## Validação
- `git diff --check`: OK. Workflow validado por parser YAML: OK.
- `gradlew :app:tasks --offline`: OK (build.gradle do app configurou).
- `gradlew :app:assembleDebug --offline`: falhou só por "SDK location not found"
  (Termux sem Android SDK); não é erro de configuração do projeto.
- Validar assinatura antes do release:
  `apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk`
  e conferir o SHA-256 do certificado entre builds.

## Auditoria do pré-lançamento
- `frey-release.jks` e `frey-release.jks.b64` existem no diretório local e são
  ignorados pelo Git; o conteúdo não foi lido.
- `RELEASE_KEYSTORE_BASE64` foi configurado no GitHub. Faltam
  `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS` e `RELEASE_KEY_PASSWORD`,
  que exigem intervenção manual.
- Fluxo separado: push/dispatch gera artefato sem publicar; só tag publica, e tag
  com hífen é pré-lançamento.

## Pendências anteriores
- `Updates.kt` já está rastreado.
- Push para `origin/main` ainda não executado; `gh` está autenticado como ADMIN.
- Atualizador ainda não consulta a rede; avaliar permissão INTERNET quando for ativado.
- Não testado em aparelho: alias LAUNCHER, primeira abertura/diálogo, 7 toques e diagnósticos.
