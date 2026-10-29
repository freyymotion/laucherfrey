---
name: lauc
description: Manutenção incremental, econômica e segura do launcher Android Frey/SimpleLauncher.
---

# Skill lauc — Frey

## Objetivo
Manter o projeto existente, preservar o que funciona e concluir tarefas com o mínimo de alterações e tokens.

## Regras obrigatórias
- Leia `PROJECT_STATE.md` nesta pasta e confirme o estado real do código antes de agir.
- Inspecione `AGENTS.md` e outras instruções aplicáveis, se existirem.
- Consulte somente os arquivos relevantes à tarefa; não releia o projeto inteiro sem necessidade.
- Nunca refaça uma funcionalidade existente sem identificar um defeito concreto.
- Prefira corrigir a causa raiz com a menor alteração segura.
- Preserve arquitetura, convenções, dependências e alterações preexistentes.
- Nunca abra para edição, apague, mova ou altere `.review-backup/`.
- Não use comandos destrutivos, `git reset`, `git clean` ou exclusões em massa.
- Não faça commit, push, tag, release ou publicação sem autorização explícita.
- Não adicione dependências sem justificar a necessidade.
- Não invente APIs, resultados de testes, informações do desenvolvedor ou estado de funcionalidades.
- Não escolha modelos pagos nem serviços que possam gerar cobrança sem autorização explícita.

## Fluxo de trabalho
1. Verifique `git status --short` e as instruções aplicáveis.
2. Consulte o registro de continuidade e os arquivos estritamente necessários.
3. Identifique o problema e confirme se a funcionalidade já existe.
4. Informe brevemente o diagnóstico, o plano e os arquivos que serão alterados.
5. Implemente apenas o escopo solicitado; agrupe correções relacionadas quando for seguro.
6. Revise o diff e execute `git diff --check`.
7. Rode os testes relevantes disponíveis. Para Android, tente o build apropriado quando possível.
8. Se uma validação falhar, registre o erro real e não declare sucesso.
9. Atualize `PROJECT_STATE.md` com mudanças, testes, falhas e próximos passos relevantes.
10. Responda em português, de forma concisa.

## Economia de tokens
- Não repita contexto já documentado.
- Não produza explicações longas quando um diagnóstico curto for suficiente.
- Não mostre arquivos completos se um diff ou trecho bastar.
- Não faça pesquisas, inspeções ou testes redundantes.
- Se faltar informação essencial, faça uma pergunta objetiva.
- Se houver bloqueio, explique a causa e proponha apenas o próximo passo útil.
- Não continue expandindo o escopo por iniciativa própria.

## Projeto
- Nome visível planejado: Frey.
- Identificador Android existente: `com.simples.launcher`; não altere sem solicitação explícita.
- Repositório: `freyymotion/laucherfrey`.
- Build Android: Java 17, Gradle e GitHub Actions.
- O ambiente Termux pode não possuir Android SDK; confirme antes de insistir em builds locais.
- O projeto já recebeu alterações na desinstalação, no visual da gaveta, no menu de pressão longa e na seção de desenvolvedor. Confira o código atual antes de modificar esses pontos.
- O recurso de wallpaper animado é apenas uma ideia futura, não uma tarefa autorizada automaticamente.

## Segurança
- Preserve arquivos e configurações não relacionados.
- Nunca exponha segredos ou credenciais.
- Não considere um arquivo ausente, uma funcionalidade concluída ou um teste aprovado sem evidência.
- Não faça alterações irreversíveis sem autorização.
