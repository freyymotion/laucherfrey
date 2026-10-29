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
Validação estática do lote anterior (identidade/atalho, primeira abertura, desenvolvedor,
modo dev com diagnósticos, base do atualizador). Nenhum erro concreto de código encontrado;
build local bloqueado por ausência de Android SDK.

## Testes e pendências
- `git diff --check`: OK. Manifesto e workflow (YAML) validados por parser: OK.
- `gradlew :app:compileDebugKotlin --offline` tentado: falhou apenas por "SDK location not found";
  Termux não tem Android SDK. JDK 17, Gradle 8.6 e wrapper jar presentes e OK.
- Revisão estática: alias LAUNCHER aponta para `.MainActivity` exportada; RoleManager só é
  usado sob `SDK_INT >= 29` (dois pontos); BuildConfig habilitado no módulo app.
- Pendência de commit: `Updates.kt` é novo e não rastreado; precisa ser incluído (`git add`) senão a CI falha.
- Não testado em aparelho: alias LAUNCHER, primeira abertura/diálogo, 7 toques, diagnósticos e layout.
- Atualizador ainda não consulta a rede; avaliar permissão INTERNET quando for ativado.
- Sem commit, push, tag ou release nesta rodada.
