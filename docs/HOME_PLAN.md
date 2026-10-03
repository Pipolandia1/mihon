# Plano — Início: uma Home focada em HQs ocidentais (fork Mihon)

> Plano de implementação do documento "Nova Home do Mihon — Foco em HQs Ocidentais", ancorado no
> código deste fork (branch `feature/bubble-zoom`, depois do Bubble Zoom E19 e das Fases A–D de
> Stats). Levantado em 25/09/2026.
>
> **Como usar:** cada passo abaixo (H1, H2, …) é **uma sessão**, como as fases do Bubble Zoom e do
> Stats. Uma sessão implementa um passo, verifica no emulador, atualiza a seção *Status* no fim deste
> arquivo e para.

---

## 0. Resumo em uma tela

- **Barra inferior: Início · Biblioteca · Histórico · Mais.** Novidades e Navegar vão para dentro de
  Mais, com as mesmas contagens. Atalhos do launcher, notificações e links internos que abriam essas
  abas continuam funcionando: passam a abrir Mais e, por cima, a tela.
- **Quase tudo que a Home precisa já está gravado** pelas Fases A–D: sessões e tempo ativo, progresso
  por capítulo, estados da biblioteca de HQ, perto de terminar, ritmo, backlog, metadados (roteiro,
  arte, personagens, equipes, arcos, editora, total de issues) e coleções. O que falta construir é um
  **motor de afinidade** (domínio puro, testado na JVM), um **seletor de blocos** e os **lançamentos**.
- **Lançamentos: GCD ou Metron.** O GCD o fork já usa. Isto foi medido em 25/09/2026, não suposto: a lista
  semanal (`/api/issue/on_sale_weekly/<ano>/week/<n>/`) tinha **270 itens** na semana corrente
  (com variantes, 6 páginas), **35** na semana seguinte e **0** duas semanas à frente. Ou seja, o GCD
  serve para **"o que sai esta semana"**, não para um calendário com semanas de antecedência. A lista
  traz só série, número e variante; editora, créditos, personagens e capa exigem um pedido por
  issue, dentro de uma cota anônima de 30 pedidos por hora. Desde 25/09/2026 o usuário tem conta e
  token no Metron, que filtra por data de venda com antecedência (§11.5).
- **Privacidade:** a lista da semana é um pedido **genérico**, igual para qualquer pessoa, baixado e
  casado com a biblioteca no aparelho. Nada da biblioteca nem da leitura sai do aparelho para montar
  a Home.
- **Sem "% compatível".** Um número sem denominador viola a regra 3 do plano de Stats ("nenhuma
  porcentagem sem denominador conhecido"). No lugar, entram os **motivos com números reais**
  ("Você terminou 3 HQs escritas por Tom King").
- **A Home recomenda também HQs fora da biblioteca** (pedido do usuário, 25/09/2026). O catálogo
  vem do **Metron**, com o token do usuário: a API filtra edições por autor, personagem e equipe.
- **Fonte principal** (pedido do usuário, 25/09/2026): uma HQ recomendada que não está na biblioteca
  é procurada **primeiro na fonte principal**, e só nas outras, na ordem escolhida, se a principal não
  tiver. O que já está na biblioteca continua na fonte em que foi adicionado; nada é migrado (§7).

---

## 1. O que já existe e a Home reaproveita

| Bloco da Home | Peça que já existe |
|---|---|
| Continuar lendo | `getHistory` + `GetNextChapters` (hoje no cartão "Continuar lendo" da aba Stats, `ReadingStatsViewModel`); página e total em `chapter_reading_progress` |
| Quase terminando | `ComicLibrary` (C3/C4): estados, perto de terminar **só com total conhecido**, séries paradas |
| "~35 min restantes", Escolha pelo tempo | `ReadingPace` (D2): minutos por capítulo, páginas por hora, estimativa condicional "no seu ritmo" |
| Do seu backlog | `ComicLibrary` (backlog, não iniciado, parado) + sessões |
| Afinidade por autor, personagem, editora | `comic_metadata` (`WRITER`, `ARTIST`, `CHARACTER`, `TEAM`, `ARC`, `PUBLISHER`, `IMPRINT`, `UNIVERSE`, `GENRE`, `TOTAL_ISSUES`, `GCD_ID`, `METRON_ID`), com origem e confirmação; `Rankings` (D3) |
| "Quero mergulhar: arco completo" | coleções (C2: run, arco, evento, ordem de leitura) |
| "Por quê?" | o padrão do `InsightRules` (D4): cada frase abre os números e a regra que a geraram |
| Lançamentos | `GcdApi` + `RequestLimiter` (C1); `IssueNumbers` (variantes, anuais, TPB, ordenação) |
| Alertas | WorkManager (como o `LibraryUpdateJob`); canal de notificação criado pelo próprio pacote |
| Achar uma HQ numa fonte | `CatalogueSource.getSearchManga`; `NetworkToLocalManga` (entrada fora da biblioteca, como ao abrir uma obra pela busca); a ordem de fontes da migração (`migrationSources`) como modelo da lista ordenada; `SmartSourceSearchEngine` como referência, com limiar bem mais estrito (§7.3) |
| Catálogo fora da biblioteca | `MetronApi` + `RequestLimiter` (C1); filtros `creator_id`, `character_id`, `team_id`, `store_date_range` da lista de issues (lidos no código do Metron em 25/09/2026) |

**As lacunas reais:**

1. **Cobertura de metadados.** Afinidade por autor ou personagem só existe onde houve busca (GCD,
   Metron) ou ComicInfo. No emulador, só o Flashpoint tem créditos. Sem metadado, o motor ainda
   funciona com série, editora pelo gênero da fonte, tamanho e comportamento de leitura, mas com
   motivos mais pobres (§5.4).
2. **Pouco dado para verificar.** O emulador tem 4 obras e um dia de leitura registrada. O H2 cria uma
   **biblioteca sintética de teste**: ~20 séries na fonte local com `ComicInfo.xml` inventados
   (roteiristas, personagens, tamanhos variados), como o "Stats ComicInfo Test". Nenhuma página real
   entra no projeto.

---

## 2. Regras deste trabalho

As mesmas do Stats (§2 do `STATS_PLAN.md`), com o que é próprio da Home:

1. **Um passo por sessão.**
2. **Código novo em pacotes próprios.** Nomes em "start" para não confundir com `HomeScreen` (o
   contêiner das abas) nem com `ReadingHomeScreen` (Stats | Histórico):
   - domínio: `domain/…/tachiyomi/domain/start/` (afinidade, candidatos, seleção de blocos, casamento
     de lançamentos), Kotlin puro;
   - dados: `data/…/tachiyomi/data/start/` + `.sq` novos;
   - app: `app/…/ui/start/` (aba Início, calendário, telas) + `app/…/presentation/start/`
     (composables) + `app/…/tachiyomi/releases/` (cliente, job, notificações).
   Arquivo upstream recebe só chamadas curtas; passou de ~15 linhas, a lógica vai para o pacote.
3. **Nunca inventar número.** Sem "% compatível". "~35 min" só com ritmo medido **e** total
   conhecido. "8 edições · história fechada" só com total e status conhecidos. Desconhecido aparece
   como desconhecido, ou a linha não aparece.
4. **A Home abre na hora.** "Continuar lendo" sai de consulta local. Blocos que dependem de rede
   (lançamentos) chegam depois e nunca seguram a tela, como a regra 3 do Bubble Zoom.
5. **Tudo explicável.** Cada recomendação carrega os motivos que a geraram, e o "Por quê?" mostra
   esses motivos com os números.
6. **Adicionar não é gostar.** Item salvo e nunca lido pesa quase nada; o usuário pode dizer "não
   tenho interesse" e isso vale.
7. **Lógica pura na JVM, com testes.** Pesos, pontuação, seleção de blocos, deduplicação, casamento
   de lançamentos, variantes, regras de alerta. Relógio injetado.
8. **Privacidade:** a rede só vê (a) pedidos genéricos (a lista da semana), (b) ao Metron, com o
   token do usuário, nomes de autores, personagens e séries que ele lê, para achar recomendações, e
   (c) às fontes, títulos de HQs do catálogo, como numa busca manual. Nunca a lista da biblioteca
   inteira. (b) e (c) têm uma preferência para desligar. Nada em `telemetry`.
9. **Verificação no emulador** com screenshots `tools/shots/home-<passo>-<n>.png` e log `StartFeed …`
   no logcat. A lista de lançamentos real é consultada no máximo algumas vezes por sessão (a cota
   anônima do GCD, 30/hora, é dividida com as buscas de metadados).
10. **Commits:** `home: <descrição imperativa>`, um por passo; o usuário commita.

---

## 3. Navegação — H1

- **Abas:** Início (nova, abre o app), Biblioteca, Histórico (Stats | Histórico, como hoje), Mais.
- **Mais** ganha dois itens no topo, "Novidades" e "Navegar", cada um com a sua contagem: capítulos
  novos não lidos e atualizações de extensão, respeitando as preferências de badge que já existem.
  A aba Mais mostra a soma das duas.
- **As telas continuam as mesmas.** O `Content()` de `UpdatesTab` e `BrowseTab` é embrulhado em uma
  `Screen` do pacote e empilhado sobre Mais. O que se perde é o "tocar de novo na aba": em Novidades
  abria a fila de downloads, em Navegar a busca global. A busca global passa para um ícone na barra
  do Início.
- **Links internos:** `HomeScreen.Tab.Updates` e `Tab.Browse(toExtensions)` continuam existindo
  (notificação de capítulos novos, notificação de extensões, atalhos do launcher,
  `MigrateSourceSearchScreen`) e passam a abrir Mais e, por cima, a tela. `MainActivity` não muda.
- **Voltar:** em qualquer outra aba, "voltar" leva ao Início (hoje leva à Biblioteca). A busca que
  abre a Biblioteca continua igual.
- **Upstream:** `HomeScreen.kt` (lista de abas, aba inicial, voltar, mapeamento de `Tab`, badge) e
  `MoreTab.kt` + `MoreScreen.kt` (dois itens). A lista de abas e o mapeamento ficam em
  `ui/start/StartNavigation.kt`, e o `HomeScreen` só chama esse arquivo.

---

## 4. Modelo de dados (migração 18)

```sql
-- Cache da lista semanal do GCD. Variantes são guardadas e agrupadas na leitura, por variant_of.
CREATE TABLE release_items(
    gcd_issue_id   INTEGER NOT NULL PRIMARY KEY,
    iso_year       INTEGER NOT NULL,
    iso_week       INTEGER NOT NULL,
    series_gcd_id  INTEGER NOT NULL,
    series_name    TEXT    NOT NULL,     -- "Absolute Batman (2024 series)"
    descriptor     TEXT    NOT NULL,     -- "24 [Clay Mann Cardstock Variant Cover]"
    variant_of     INTEGER,              -- NULL = capa regular
    fetched_at     INTEGER NOT NULL
);
CREATE INDEX release_items_week_idx ON release_items(iso_year, iso_week);

-- Detalhe de um issue, buscado só para os itens relevantes (cota): dia, capa, editora, créditos.
CREATE TABLE release_details(
    gcd_issue_id   INTEGER NOT NULL PRIMARY KEY,
    on_sale_date   TEXT,                 -- 'YYYY-MM-DD' quando o GCD sabe
    cover_url      TEXT,
    publisher      TEXT,
    credits        TEXT,                 -- linhas "papel\tnome"
    characters     TEXT,
    fetched_at     INTEGER NOT NULL
);

-- O que o usuário pediu para acompanhar.
CREATE TABLE release_follows(
    _id            INTEGER NOT NULL PRIMARY KEY,
    kind           INTEGER NOT NULL,     -- 0 edição, 1 série, 2 personagem, 3 autor
    follow_key     TEXT    NOT NULL,     -- id do issue/série no GCD, ou o nome normalizado
    label          TEXT    NOT NULL,
    manga_id       INTEGER REFERENCES mangas(_id) ON DELETE SET NULL,
    created_at     INTEGER NOT NULL,
    UNIQUE(kind, follow_key)
);

-- Para nunca avisar duas vezes a mesma coisa.
CREATE TABLE release_notified(
    gcd_issue_id   INTEGER NOT NULL,
    what           INTEGER NOT NULL,     -- 0 saiu, 1 já disponível numa fonte
    notified_at    INTEGER NOT NULL,
    PRIMARY KEY(gcd_issue_id, what)
);

-- O que o usuário disse de uma recomendação.
CREATE TABLE start_feedback(
    subject_kind   INTEGER NOT NULL,     -- 0 obra, 1 autor, 2 personagem
    subject_key    TEXT    NOT NULL,     -- manga_id, ou nome normalizado
    verdict        INTEGER NOT NULL,     -- 0 não tenho interesse, 1 já li, 2 agora não
    until_at       INTEGER,              -- "agora não" volta depois de uma data
    created_at     INTEGER NOT NULL,
    PRIMARY KEY(subject_kind, subject_key)
);
```

```sql
-- Onde uma HQ do catálogo foi achada nas fontes (§7). Um "não achado" também é guardado, para não
-- buscar de novo a cada abertura do Início.
CREATE TABLE start_source_matches(
    catalog_key    TEXT    NOT NULL PRIMARY KEY,  -- "metron:series:1234"
    source_id      INTEGER,                       -- NULL = não achada em nenhuma fonte
    manga_url      TEXT,
    manga_id       INTEGER REFERENCES mangas(_id) ON DELETE SET NULL,  -- entrada fora da biblioteca
    matched_title  TEXT,
    tried_sources  TEXT    NOT NULL,              -- ids das fontes procuradas, na ordem
    checked_at     INTEGER NOT NULL,
    expires_at     INTEGER NOT NULL
);
```

**Backup:** acompanhamentos, feedback e a ordem de fontes entram no `BackupReadingStats` (campos
novos 8, 9 e 10), como os metadados e as coleções na Fase C. Os caches (lançamentos, casamentos nas
fontes) não entram no backup.

---

## 5. Motor de afinidade — H3

### 5.1 Sinais e pesos

Os pesos são os do documento; os números abaixo são o ponto de partida, calibrados na biblioteca
sintética e depois no aparelho do usuário.

| Força | Sinal (por obra) | De onde vem |
|---|---|---|
| muito forte | série concluída | `ComicLibrary` (estado concluído, total conhecido) |
| muito forte | muitas edições lidas (≥ 10) | sessões com conclusão + marcados à mão |
| forte | mesmo autor ou personagem em ≥ 3 obras lidas | `comic_metadata` × sessões |
| médio | em andamento, lida parcialmente, na biblioteca com leitura | sessões, `chapters.read` |
| fraco | aberta uma vez (1 sessão, < 5 min ativos) | sessões |
| quase nada | adicionada e nunca lida | biblioteca |
| negativo | "não tenho interesse"; abandonada cedo (< 25 % e parada há 60+ dias) só como leve | `start_feedback`, `ComicLibrary` |

- **Recência:** o peso de uma leitura cai à metade a cada 180 dias. Leitura de dois anos atrás ainda
  conta, mas menos.
- **Da obra para as entidades:** a afinidade de cada obra é repartida entre as suas entidades (cada
  roteirista, desenhista, personagem, equipe, editora, selo, universo, gênero), com peso por papel:
  roteiro > personagem principal > arte > equipe > editora/selo > gênero.
- **Peso pela origem do metadado:** confirmado 1,0; Metron, GCD e ComicInfo 0,8; fonte 0,5;
  inferido 0. Inferido nunca sustenta uma recomendação sozinho.
- **Hábitos de formato**, medidos e não supostos: a taxa de conclusão por tamanho ("terminou 4 de 5
  séries com até 12 edições"). Só entra como motivo com pelo menos 3 séries naquela faixa.

### 5.2 Candidatos e pontuação (V1)

- **Candidatos:** no H3, obras da biblioteca não concluídas (não iniciadas, paradas, em andamento
  que não sejam o "Continuar lendo") e coleções com item não lido. No H5 entram as séries do catálogo
  (Metron) dos autores, personagens e equipes com afinidade forte, que só aparecem depois de achadas
  numa fonte (§7).
- **Pontuação:** soma das afinidades das entidades que o candidato compartilha com o histórico, mais
  o encaixe de formato, menos o feedback. Empates são decididos por recência de adição e pelo sorteio
  do dia (§6), para a Home não ficar idêntica todos os dias.
- **Diversidade:** o mesmo autor ou a mesma série não ocupa mais de um destaque na tela.

### 5.3 Explicação

Cada pontuação guarda as 2 ou 3 maiores contribuições como frases com o número real por trás:
"Você terminou 3 HQs escritas por Tom King", "Você costuma terminar minisséries: 4 de 5 com até 12
edições", "Mesmo personagem de Batman (2025-), que você leu 14 edições". O "Por quê?" mostra essas
frases e de onde veio cada dado, como os Insights.

### 5.4 Cobertura de metadados

Quando a maior parte da biblioteca não tem autor nem personagem, a Home mostra uma linha discreta
("As recomendações melhoram com metadados: 12 séries sem roteirista") que abre a Biblioteca de HQ
existente. A busca em lote é decisão em aberto (§11.7), porque o plano de Stats decidiu "só por ação
explícita, uma série por vez".

---

## 6. Seleção de blocos — H2, crescendo a cada passo

- **Ordem fixa** da V1 do documento: Continuar lendo, Leia isso agora, Lançamentos da semana,
  Porque você gostou de X, Quase terminando, Escolha pelo tempo, Do seu backlog, Autores e
  personagens.
- **5 a 7 blocos por sessão.** Cada bloco tem regra de elegibilidade (tem conteúdo? o conteúdo é
  bom o bastante?) e relevância. Entram os elegíveis mais relevantes, na ordem fixa. Continuar lendo
  entra sempre que houver.
- **Uma obra aparece uma vez na tela.** O bloco mais alto fica com ela, e os de baixo pegam a
  próxima.
- **Estável dentro de uma sessão.** A seleção é calculada ao abrir o app, ou ao voltar depois de 4
  horas, e não a cada recomposição. Puxar para atualizar recalcula. O "Porque você gostou de X"
  alterna o X pelo sorteio do dia, com semente na data.
- **Usuário novo ou sem leitura:** sem Continuar lendo, a Home abre com Do seu backlog, Escolha pelo
  tempo e Lançamentos. Nunca uma tela vazia.

---

## 7. HQs fora da biblioteca e a fonte principal — H4 e H5

### 7.1 O pedido

"HQs que a Home recomenda e que não fui eu quem adicionou devem vir preferencialmente da fonte
principal; de outra só se a principal não tiver." Portanto:

- vale para o que a Home traz de fora da biblioteca: recomendações do catálogo e, no H7,
  lançamentos de séries que não estão na biblioteca;
- o que o usuário adicionou fica na fonte em que ele adicionou. Nada é migrado, e o Início nunca
  adiciona nada à biblioteca sozinho.

### 7.2 Onde se escolhe

Uma lista ordenada, **"Fontes das recomendações"**: a principal no topo e as reservas abaixo, na
ordem em que devem ser tentadas. Tela própria do pacote, aberta pelo menu (⋮) do Início, no mesmo
modelo da lista de fontes da migração (arrastar para ordenar, ligar e desligar). Por padrão, a lista
vem vazia e o Início pede para escolher a principal na primeira recomendação de fora da biblioteca.
Um atalho "copiar da ordem da migração" preenche a lista.

### 7.3 Como uma HQ do catálogo vira uma obra numa fonte

1. O catálogo dá a série: nome, ano de início, editora e id do Metron.
2. **Busca na fonte principal** pelo nome.
3. **Casamento estrito**, puro e testado: nome normalizado igual (sem ano entre parênteses, "Vol.",
   pontuação, artigo inicial) **e** ano igual quando os dois lados têm ano (`Absolute Batman (2024-)`
   ↔ Metron `Absolute Batman`, 2024). Quase igual (Levenshtein ≥ 0,9) só vale com ano igual. O
   limiar 0,4 da migração serve para quem confere antes de migrar, não para escolher sozinho: uma
   recomendação da série errada é pior do que nenhuma.
4. **Sem resultado, ou a fonte falhou** (Cloudflare, erro, tempo esgotado): próxima fonte da lista.
5. **Achou:** `NetworkToLocalManga` cria a entrada **fora da biblioteca**, como quando se abre uma
   obra pela busca. O cartão mostra "em Read Comics Online", e o toque abre a página da obra.
6. **Guarda o resultado** (`start_source_matches`): achado por 7 dias, "não achado" por 1 dia.
7. **Só o que vai aparecer na tela é procurado** (5 a 10 séries por sessão), em fila, fora da thread
   principal. O cartão aparece quando a série é achada; a que não é achada em nenhuma fonte não
   aparece.

### 7.4 Custo e privacidade

Procurar numa fonte envia o nome da HQ ao site, como uma busca manual; aqui é automático, então: só
nomes vindos do catálogo (nunca a lista da biblioteca), no máximo 10 séries por sessão, e a
preferência "Procurar recomendações nas fontes" desliga tudo isso (a Home fica só com a biblioteca).

### 7.5 O catálogo (Metron) — H5

- Das leituras: os autores, personagens e equipes com afinidade forte (§5). Nome → id do Metron pela
  busca de criador ou personagem, guardado em cache; séries de uma obra já buscada na Fase C têm o
  `METRON_ID`.
- Id → edições com `creator_id` / `character_id` / `team_id` → as séries delas, sem as que já estão
  na biblioteca ou foram lidas, e sem o que o usuário recusou.
- Pontuação pelo motor do H3, com o motivo ("Mais Tom King: você terminou 3 HQs dele") e o formato
  quando o Metron sabe ("minissérie · 12 edições").
- Limites do Metron: 30 pedidos por minuto e 10.000 por dia. O Início faz poucas dezenas por dia, em
  cache.

---

## 8. Lançamentos — H6 e H7

### 8.1 A fonte (medida em 25/09/2026)

| Fonte | O que tem | Custo e limite | Situação |
|---|---|---|---|
| **GCD** (já no fork) | lista por semana ISO: série, número, variante; detalhe por issue com dia, capa, créditos e personagens | sem cadastro: **30 pedidos/hora**; com login do usuário, 2.000/dia | semana corrente completa na sexta (270 itens); próxima semana **parcial** (35); duas semanas à frente, **nada** |
| **Comic Vine** | issues com `store_date`, filtro por intervalo, meses à frente; créditos e personagens | chave gratuita **do usuário**; 200 pedidos por recurso por hora; uso **não comercial**, cache e link de volta obrigatórios | não testado aqui (sem chave) |
| **Metron** | filtro `store_date_range_after/before`; créditos, personagens, arcos | conta do usuário; 30/min e 10.000/dia | o usuário tem conta e token desde 25/09/2026 |
| League of Comic Geeks | calendário completo | sem API pública; raspar o site fere os termos | fora |

**Recomendação (revista em 25/09/2026, com o token do Metron):** Metron como fonte principal dos
lançamentos, com semanas de antecedência e autor e personagem em cada edição; GCD quando o Metron
falhar. Decidir antes do H6 (§11.5).

Consequência honesta para a tela, se for o GCD: o bloco é **"Esta semana"**, mais **"Próxima
semana (parcial)"** quando houver itens. O dia exato vem do detalhe do issue, só para os itens buscados; na
lista, a granularidade é a semana.

### 8.2 Casamento com a biblioteca

- **Série:** pelo `GCD_ID` guardado na Fase C, que é exato. Sem ele, por nome normalizado e ano
  ("Absolute Batman (2024 series)" ↔ "Absolute Batman (2024-)"), marcado como provável.
- **Variantes** agrupadas por `variant_of`: um issue aparece uma vez. Anuais, especiais e
  encadernados passam pelo `IssueNumbers`.
- **Relevância:** série na biblioteca ou acompanhada > série já lida > personagem ou autor
  acompanhado (exige o detalhe) > #1 nova de editora ou personagem recorrente (exige o detalhe).
- **Detalhes só para os primeiros N itens relevantes**, dentro do `RequestLimiter`, com cache de 30
  dias. O resto aparece sem capa, com série e número.
- **Pedidos:** a semana corrente é baixada de novo no máximo uma vez por dia (o GCD a completa ao
  longo da semana); semanas passadas nunca mais são baixadas. São ~6 pedidos por dia.

### 8.3 Alertas e "já disponível" — H7

- **Opções do sino:** só esta edição; todos os próximos números da série; o personagem; o autor.
  Personagem e autor só casam com lançamentos cujo detalhe foi buscado: com a cota anônima do GCD, a
  tela diz isso claramente.
- **Job diário** (WorkManager), agendado pelo próprio pacote ao criar o primeiro alerta. Nenhum
  arquivo upstream muda. Ele baixa a semana, compara com os acompanhamentos e avisa uma vez só:
  "Absolute Batman #24 saiu esta semana".
- **"Já disponível — Ler agora":** para séries da biblioteca, quando a atualização da biblioteca do
  Mihon traz um capítulo com o número do issue acompanhado. O job consulta capítulos com
  `date_fetch` posterior à última verificação, sem mexer no `LibraryUpdateJob`. O toque abre o
  capítulo. Para séries fora da biblioteca, pelo mesmo caminho do §7: procurada na fonte principal
  e, se não estiver, nas reservas.
- **Canal de notificação** "Lançamentos", criado pelo pacote e desligável nas configurações do
  sistema.

---

## 9. Telas

### 9.1 Início (ordem V1)

1. **Continuar lendo:** um cartão grande (capa, série, edição, "página 12 de 24", progresso na série
   quando o total é conhecido) que abre o leitor na página exata. Logo abaixo, até 3 obras recentes
   em linha.
2. **Leia isso agora:** uma obra, com formato ("8 edições · história fechada", só se conhecido), 2
   motivos e os botões [Começar a ler] [Por quê?]. Um toque longo abre "Não tenho interesse · Já li
   · Agora não".
3. **Lançamentos da semana:** cartões com série, número, editora e dia (quando conhecidos), o motivo
   ("Porque você lê Batman") e o sino. "Ver todos →" abre o calendário.
4. **Porque você gostou de X:** 4 a 8 capas com uma linha de motivo cada; de fora da biblioteca a
   partir do H5, com "em <fonte>".
5. **Quase terminando:** "Faltam 2 edições · Supergirl: Woman of Tomorrow · 6/8 · ~35 min no seu
   ritmo" (os minutos só com ritmo medido).
6. **Escolha pelo tempo:** chips ⚡ 10 min, ☕ 30 min, 🌙 Mergulhar, ✅ Terminar algo. Cada chip abre
   uma lista curta. 10 e 30 min usam os minutos por edição medidos (D2); sem ritmo medido, usam
   páginas com um aviso ("estimado pelas páginas"). "Mergulhar" usa arcos e coleções; "Terminar
   algo", o perto de terminar.
7. **Do seu backlog:** "Você adicionou e nunca começou", "Volte para uma série parada", "Histórias
   curtas que você já tem".
8. **Autores e personagens:** "Você lê muito Jonathan Hickman", com as obras dele ainda não lidas:
   as da biblioteca e, a partir do H5, as de fora dela, achadas na fonte principal (§7), com "em
   <fonte>" no cartão.

A barra do Início tem busca global (o atalho que a aba Navegar dava) e atualizar.

### 9.2 Calendário de lançamentos

Lista por semana e por dia, quando o dia é conhecido. Relevantes primeiro, com o motivo; o resto da
semana abaixo, recolhido. Filtro "só o que eu acompanho". Crédito do GCD (CC BY-SA 4.0) no rodapé.

### 9.3 Estados

Sem dados, carregando, sem rede (lançamentos: "sem conexão — mostrando a lista de <data>"), cota do
GCD esgotada ("volta em <hora>"). Acessibilidade como no Stats: descrição textual por cartão, toque
≥ 48 dp, fonte a 200 %, claro e escuro.

---

## 10. Passos

Cada passo: **arquivos**, **testes**, **verificação**, **aceite**. ⬆ marca arquivo upstream, que
precisa de aprovação antes de editar.

**H1 — Navegação.** `ui/start/StartTab.kt` (Início provisório, só com Continuar lendo),
`ui/start/StartNavigation.kt`, telas-invólucro de Novidades e Navegar; `HomeScreen.kt` ⬆,
`MoreTab.kt` ⬆, `MoreScreen.kt` ⬆, strings. Testes: mapeamento `Tab` → destino (JVM).
Verificação: as quatro abas; badges em Mais e nos itens; notificação de capítulo novo e de
extensão abrindo a tela certa; atalhos do launcher; voltar leva ao Início; Biblioteca intacta.
**Aceite:** nenhuma tela de Novidades ou Navegar ficou inalcançável ou mudou de conteúdo.

**H2 — Esqueleto do Início com os blocos que não precisam do motor.** Continuar lendo, Quase
terminando, Do seu backlog, Escolha pelo tempo; seletor de blocos (§6) com deduplicação; biblioteca
sintética de teste na fonte local. Testes: seletor (elegibilidade, máximo de blocos, uma obra uma
vez, estabilidade por sessão, sorteio do dia), estimativas só com ritmo medido. **Aceite:** o
Início abre com Continuar lendo antes de qualquer outra coisa (tempo medido no logcat) e nenhum
número aparece sem base.

**H3 — Motor de afinidade, "Leia isso agora" e "Por quê?".** `domain/start/Affinity*`,
`Candidates`, `Reasons`; `start_feedback` (migração 18, parte) e backup do feedback. Testes: cada
sinal e peso, recência, origem do metadado, "adicionar não é gostar", feedback que tira da tela,
motivos com os números certos. **Aceite:** na biblioteca sintética, a escolha e os motivos batem
com o histórico montado para ela.

**H4 — Fonte principal e busca nas fontes.** Tela "Fontes das recomendações" (§7.2), resolvedor
(§7.3), `start_source_matches` e backup da ordem de fontes. Testes: casamento estrito (nomes reais
das fontes do emulador: `Absolute Batman (2024-)`, `Batman (2025-)`, `Flashpoint (2011)`), ano,
ordem das fontes, fonte que falha passa para a próxima, cache de achado e de não achado, nunca
favorita nada. Verificação: uma série conhecida achada na principal; com a principal sem ela, achada
na reserva; com a principal atrás do Cloudflare, idem, e o log `StartFeed` diz por quê.

**H5 — Descoberta pelo catálogo (Metron).** Catálogo (§7.5), "Porque você gostou de X" e "Autores e
personagens" com obras de dentro e de fora da biblioteca, e a linha de cobertura de metadados.
Testes: nome → id, séries por autor e personagem com respostas gravadas a partir do modelo do Metron,
exclusão do que já está na biblioteca ou foi recusado, diversidade. **Aceite:** toda recomendação de
fora da biblioteca abre uma obra da fonte principal, ou de uma reserva quando a principal não a tem.

**H6 — Lançamentos da semana.** Fonte conforme a §11.5, cache (`release_items`, `release_details`),
casamento (§8.2), bloco e calendário. Testes: agrupamento de variantes com a resposta real do GCD
gravada como fixture, casamento por id e por nome e ano, relevância, uso de cota. **Aceite:**
Absolute Batman #24 aparece uma vez (não 6 vezes pelas capas variantes), como "Porque você lê
Absolute Batman".

**H7 — Alertas e notificações.** `release_follows`, `release_notified`, sino e opções, job diário,
"já disponível" (biblioteca pelo número do capítulo; fora dela pelo resolvedor do H4), canal, backup
dos acompanhamentos. Testes: regras de alerta, nunca duas vezes. Verificação: notificação disparada
com o relógio do job injetado.

---

## 11. Decisões em aberto (propostas padrão entre parênteses)

> No H1 foram adotados os padrões de 1 a 8 (o usuário pediu o H1 sem mudá-los). A 9 foi respondida
> pelo usuário em 25/09/2026. A 5 e as de 10 a 13 continuam abertas.

1. **Barra.** A mensagem do usuário diz "Início, Biblioteca, Histórico e Mais"; o documento sugere
   "Home · Biblioteca · Explorar · Stats". (A da mensagem: Stats já mora em Histórico, e Explorar é
   o Navegar que vai para Mais.)
2. **O Início abre o app e é o destino do "voltar"?** (sim)
3. **Badge da aba Mais** = novidades + extensões, respeitando as preferências de badge? (sim)
4. **"% compatível" trocado por motivos** com números reais? (sim)
5. **Fonte de lançamentos:** respondida pelo usuário em 26/09/2026 — Metron como fonte principal dos
   lançamentos, GCD quando o Metron falhar.
6. **"Continuar lendo" e "Perto de terminar" saem da aba Stats** e ficam só no Início? (sim; Stats
   fica com números, metas e atividade)
7. **Metadados em lote:** permitir "buscar metadados de todas as séries" como ação explícita,
   enviando só nome e ano, em fila dentro do limite do GCD? (sim, com o progresso visível e
   cancelável)
8. **Job de lançamentos:** diário, em qualquer rede (~6 pedidos pequenos)? (sim)
9. **Descoberta fora da biblioteca:** respondida pelo usuário — entra, e as HQs de fora da
   biblioteca vêm preferencialmente da fonte principal (§7). Virou os passos H4 e H5.
10. **Ordem das fontes:** lista própria "Fontes das recomendações" (principal + reservas), com atalho
    para copiar a ordem da migração, em vez de usar a da migração diretamente? (sim: as duas servem a
    coisas diferentes)
11. **HQ recomendada que não está em nenhuma fonte:** esconder? (sim; a outra opção seria mostrar
    "não achada nas suas fontes")
12. **Tocar numa recomendação de fora da biblioteca:** abre a página da obra, de onde se lê ou
    adiciona? (sim; a outra opção seria abrir direto o leitor no #1)
13. **Consultas automáticas ao Metron e às fontes** para as recomendações, com uma preferência para
    desligar? (sim; desligado, a Home recomenda só da biblioteca)

---

## 12. Arquivos upstream que o plano toca

| Arquivo | Passo | Por que não dá para ficar no pacote |
|---|---|---|
| `ui/home/HomeScreen.kt` | H1 | é quem define as abas, a aba inicial, o voltar e os badges |
| `ui/more/MoreTab.kt`, `presentation/more/MoreScreen.kt` | H1 | a lista de itens de Mais |
| `ui/updates/UpdatesTab.kt`, `presentation/updates/UpdatesScreen.kt`, `ui/browse/BrowseTab.kt`, `presentation/components/TabbedScreen.kt` | H1 | a seta de voltar quando as telas abrem a partir de Mais: parâmetro opcional `navigateUp`, nulo por padrão (aprovado pelo usuário em 25/09/2026) |
| `icons/material-symbols/…/rounded/home.svg`, `roundedFilled/home.svg` | H1 | ícone da aba Início; arquivos novos, nenhum existente alterado |
| `strings.xml` (base, pt-rBR) | todos | recursos de texto do projeto |

Nenhum outro: `MainActivity`, notificações existentes, `LibraryUpdateJob`, leitor e Bubble Zoom
ficam intactos. O cartão "Continuar lendo" do Stats está no pacote da feature.

## 13. Arcos e eventos — H8 a H11

### 13.1 O pedido (03/10/2026)

"Adicione uma seção na Home com eventos/arcos recentes e de preferência baseado no que estou lendo,
e eu posso acessar a página de um arco e ler todas as HQs dele em ordem vindo da minha fonte
principal. E eu posso seguir um arco para me avisar quando lançar uma nova HQ daquele arco e também
aparecer em Continuar lendo. Além disso deve ter um rastreador de quantos % eu li do arco e quantas
HQs faltam."

### 13.2 O que já existe (lido no código do Metron e do fork em 03/10/2026)

- **Metron:** `/api/arc/?name=` (busca por nome), `/api/arc/<id>/` (nome, descrição, imagem) e
  `/api/arc/<id>/issue_list/` — as edições do arco **ordenadas por data de capa, série e número**,
  cada uma com a série (id, nome, ano), número, data de venda e capa, a mesma forma que o H6 já lê.
  No Metron, "evento" também é um arco. A lista de edições **não** filtra por arco (não há `arc_id`):
  seguir um arco é reler a lista dele.
- **"O que estou lendo":** o detalhe de cada edição traz os arcos, e a Fase C do Stats já grava o arco
  de cada capítulo com metadados (`ComicField.ARC`, por nome).
- **Coleções do Stats** (arco, evento, ordem de leitura) são montadas à mão com capítulos da
  biblioteca, e o Mergulhar as mostra. O arco do Metron é outra coisa: a lista completa, inclusive o
  que não está na biblioteca. As duas convivem.
- **Ler pela fonte principal:** o H4 acha a série nas fontes (principal primeiro), o
  `UpdateMangaFromRemote` traz os capítulos de uma entrada fora da biblioteca (como abrir a página da
  obra) e o `Alerts.chapterFor` acha a edição pelo número.
- **Avisos:** H6 e H7 (lista da semana, acompanhamentos, job diário, "saiu" e "já disponível").
- **O leitor não passa sozinho de uma série para outra** (nem com as coleções).

### 13.3 Modelo (migração 23)

```sql
-- Um arco do catálogo, e quando foi pedido.
CREATE TABLE start_arcs(
    arc_key      TEXT NOT NULL PRIMARY KEY,  -- "metron:arc:123"
    name         TEXT NOT NULL,
    description  TEXT,
    image_url    TEXT,
    fetched_at   INTEGER NOT NULL
);

-- As edições do arco, na ordem do Metron.
CREATE TABLE start_arc_issues(
    arc_key      TEXT NOT NULL,
    position     INTEGER NOT NULL,
    issue_key    TEXT NOT NULL,              -- "metron:issue:456"
    series_key   TEXT NOT NULL,
    series_name  TEXT NOT NULL,
    series_year  INTEGER,
    number       TEXT NOT NULL,
    cover_date   TEXT,
    store_date   TEXT,
    cover_url    TEXT,
    PRIMARY KEY(arc_key, position)
);
```

Onde cada série do arco foi achada já fica em `start_source_matches` (H4). Cache de 1 dia para arcos
seguidos ou com edição saindo, 7 dias para os outros; fora do backup (o seguir vai no campo 8, e o
lido é o dos próprios capítulos).

### 13.4 O rastreador

- **Total** = as edições do arco no Metron (uma por edição, sem variantes). **Lidas** = as edições cujo
  capítulo está lido no Mihon — na entrada que o Início achou ou na obra da biblioteca que é a mesma
  série (id do catálogo, ou nome e ano), seja qual for a fonte.
- "8 de 12 · 67 % · faltam 4", com barra. Sem o total do Metron, nada de porcentagem (regra 3).
- Edições que ainda não saíram contam no total e aparecem como "sai em 14/10"; as que nenhuma fonte
  tem aparecem como "não achada nas suas fontes" e também contam: a porcentagem é do arco, não do que
  a fonte tem.

### 13.5 Passos

**H8 — Página do arco e leitura em ordem.** Domínio `Arcs.kt` (puro): as edições em ordem, o estado
de cada uma (lida · disponível em <fonte> · não achada · ainda não saiu), o progresso e a próxima a
ler. Dados: migração 23 e `ArcRepository`. App: `MetronApi` (busca, detalhe e lista do arco);
`ArcReading` — cada série do arco procurada uma vez pelo H4 (principal primeiro), os capítulos
trazidos e as edições casadas pelo número, em fila e aparecendo aos poucos —; tela **"Arco"** (imagem,
nome, descrição, barra, "Continuar o arco", a lista em ordem; tocar abre o leitor na edição). Porta
provisória: os arcos de um lançamento (tela Lançamentos). Testes: ordem, estados, progresso com e sem
total, edição lida em outra fonte conta, variantes, ainda não saiu. **Aceite:** "Continuar o arco"
abre a próxima edição não lida, da fonte principal quando ela tem.

**H9 — "Arcos e eventos" no Início.** Candidatos: (1) arcos seguidos em andamento; (2) arcos das
edições lidas recentemente (o `ARC` dos metadados; nome → id pelo Metron, em cache); (3) arcos dos
lançamentos da semana que importam (H6; o detalhe só das relevantes). Ordem: seguidos, depois os do
que você lê (mais recente primeiro), depois os da semana. Cartão: imagem, nome, "8 de 12 · faltam 4"
ou "#3 saiu esta semana", e o motivo ("Porque você lê Absolute Batman"). Bloco novo na ordem fixa
depois de Lançamentos; com 9 tipos, o corte segue a regra do H6. Testes: candidatos, ordem, motivos,
nada recusado.

**H10 — Seguir um arco.** `FollowKind.ARC` ("metron:arc:123") pelo sino da página e do cartão. O job
diário do H7 relê a lista de cada arco seguido (um pedido por arco por dia, até 10): edição nova à
venda → "Absolute Batman #25 saiu · arco The Zoo"; depois o "já disponível · Ler agora" do H7.
**Continuar lendo:** um arco seguido com próxima edição disponível aparece lá ("Arco The Zoo ·
próximo: Absolute Batman #8 · 7 de 12"), ordenado pela última leitura junto com as obras; tocar abre a
edição. Testes: edição nova do arco, nunca duas vezes, o arco no Continuar lendo sem repetir a obra
que já está lá. Backup: o seguir vai no campo 8.

**H11 — (opcional) Leitor: "próxima do arco".** Ao terminar uma edição aberta pelo arco, oferecer a
próxima — de outra série. Exige mexer no leitor (`ReaderViewModel`/`ReaderActivity`, upstream): só
com aprovação.

### 13.6 Decisões em aberto (propostas padrão entre parênteses)

14. **Ordem de leitura:** a do Metron (data de capa, série, número)? (sim; as listas de leitura
    curadas do Metron ficam para depois)
15. **Edição que nenhuma fonte tem** conta no total? (sim: a porcentagem é do arco)
16. **Seguir ou ler um arco** põe as séries dele na biblioteca? (não: entradas fora dela, como no
    H4/H5; nada é adicionado sozinho)
17. **Arco seguido no Continuar lendo antes de começar?** (sim, como "Começar o arco", quando a
    primeira edição está disponível)
18. **H11, o leitor que passa para a próxima do arco?** (a decidir: mexe em arquivo upstream)

---

## Status

- [x] H1 navegação — `StartTab`, `StartNavigation`, `StartRoutes`, `MoreUpdatesScreen`/`MoreBrowseScreen`,
  `ContinueReadingSource` (Continuar lendo compartilhado com a aba Stats)
- [x] H2 esqueleto do Início — `StartFeed`, `ContinueRules`, `ReadingTime` (domínio), `StartFeedSource`,
  blocos Quase terminando, Escolha pelo tempo e Do seu backlog; Continuar lendo esconde o que foi lido
  até o fim e volta com edição nova; Continuar lendo e Perto de terminar saíram da aba Stats
- [x] H3 motor de afinidade, "Leia isso agora", "Por quê?", feedback — `Affinity`, `Recommender`,
  `StartRepository` (domínio), `start_feedback` (migração 18) e backup do feedback (campo 9)
- [x] H4 fonte principal e busca nas fontes — `TitleMatch`, `SourceResolver` (domínio), `start_source_matches`
  (migração 19), `SourceFinder`, tela "Fontes das recomendações" com "Testar uma busca"
- [x] H5 descoberta pelo catálogo (Metron): "Porque você gostou de X", autores e personagens — `Discovery`
  (domínio), `start_catalog_cache` (migração 20), `CatalogDiscovery`, pedidos de catálogo no `MetronApi`
- [x] H6 lançamentos da semana + calendário — `Releases` (domínio), `release_weeks`/`release_items` (migração 21),
  `ReleaseFeed` (Metron, GCD de reserva), bloco "Lançamentos da semana" e tela "Lançamentos"
- [x] H7 alertas e notificações — `Alerts` (domínio), `release_follows`/`release_notified` (migração 22),
  `ReleaseAlerts`, `ReleaseAlertJob`, `ReleaseNotifier`, sino e tela "Alertas", backup (campo 8)
- [x] H8 página do arco, leitura em ordem e rastreador — `Arcs` (domínio), `start_arcs`/`start_arc_issues`
  (migração 23), `ArcCatalog`, `ArcReading`, telas "Arcos e eventos" (busca) e "Arco"
- [x] H9 "Arcos e eventos" no Início — `ArcShelf` (domínio), `ArcShelfSource`, bloco depois de Lançamentos,
  "Não tenho interesse neste arco" (`FeedbackSubject.ARC`)
- [ ] H10 seguir um arco: avisos e Continuar lendo
- [ ] H11 (opcional) leitor: próxima do arco

### Verificação do H1 (25/09/2026)

**Como ficou:**
- Barra Início · Biblioteca · Histórico · Mais; o app abre no Início; voltar de qualquer aba leva ao
  Início; tocar de novo no Início abre a busca global (o atalho que a aba Navegar tinha).
- Mais: "Atualizações" e "Navegar" no topo, cada um com a contagem como subtítulo; a aba Mais mostra a
  soma. Atualizações e Navegar abrem como telas com seta de voltar (o `Content()` das abas, com
  `navigateUp`). Tocar de novo em Atualizações (abria a fila de downloads) deixou de existir.
- Pedidos de aba (`HomeScreen.Tab`) passam por `StartRoutes` (puro): Atualizações e Navegar viram Mais
  + a tela; Biblioteca, Histórico, obra e fila de downloads como antes. `MainActivity` não mudou.
- Início provisório: Continuar lendo (um destaque com capa, capítulo, página, barra de progresso só com
  o total conhecido e botão "Continuar"; mais 3 obras em lista) e estado vazio com "Abrir a
  biblioteca". A splash espera o Início carregar, como esperava a Biblioteca.
- `ContinueReadingSource` saiu do `ReadingStatsViewModel`; Stats e Início leem a mesma lista.

**Testes na JVM:** `StartRoutesTest` (5: notificação de capítulos novos, notificação de extensões,
atalho de fontes, abas que ficaram, obra e fila de downloads); 347 testes do app passando.

**Emulador** (`tools/shots/home-h1-*.png`, pt-BR e tema escuro como o app estava):
- abre no Início com as quatro abas; Continuar lendo com Flashpoint: The World of Flashpoint
  ("Cap. 1 · pág. 1 de 20", barra), Absolute Batman, Flashpoint e Batman (2025-) "Próximo: cap. 2";
- Mais com Atualizações e Navegar no topo; Atualizações abre com seta e a seta volta a Mais;
- badge: com uma atualização de extensão pendente, a aba Mais mostrou "1" e Navegar o subtítulo
  "Atualização de extensão disponível";
- intents das notificações (`SHOW_RECENTLY_UPDATED`, `EXTENSIONS`) com o app aberto no Início:
  abriram Atualizações e Navegar **na sub-aba Extensões**, ambos sobre Mais;
- voltar de Mais foi para o Início; Histórico (Stats | Histórico) com o Continuar lendo igual ao do
  Início; Biblioteca intacta;
- "Continuar" abriu o leitor no capítulo e na página certos (a página não carregou: a fonte online
  está atrás do Cloudflare, erro da fonte, não do H1); tocar de novo no Início abriu a busca global;
  nenhum crash.

**Não verificado:** badge de capítulos novos com número (não havia capítulos novos no emulador; o
caminho é o mesmo do badge de extensões, coberto pela mesma soma); atalhos do launcher (mesmos intents
das notificações); tablet (trilho lateral: a mesma lista de abas); tema claro; TalkBack.

### Verificação do H2 (25/09/2026)

**Pedido do usuário no meio do passo:** "o Continuar lendo tem que sumir uma HQ que já li tudo e ela
deve voltar quando lançar novos issues". A causa era o próprio Mihon: o "próximo capítulo" dele é o que
vem depois do último aberto, lido ou não — uma série relida ou marcada à mão ficava na lista com uma
edição já lida como "próximo". `ContinueRules` (domínio, puro): abre o capítulo deixado pela metade ou
o primeiro **não lido** depois dele; sem nenhum, a obra sai da lista; um capítulo que chega depois da
última leitura a traz de volta **no topo**, com "Nova edição: cap. N". Vale também para a aba Stats,
que usa a mesma fonte. Olha as 40 obras lidas mais recentemente (`CONTINUE_READING_SCAN`) e, além
delas, qualquer obra da biblioteca que recebeu capítulo depois de lida, por mais antiga que seja.

**Como ficou (e o que mudou em relação ao §6 e §9.1):**
- Blocos na ordem fixa, no máximo 7, sem bloco vazio; uma obra aparece uma vez (Quase terminando e o
  backlog não repetem o que está acima). **Exceção:** as listas da Escolha pelo tempo, que só aparecem
  ao tocar num chip e respondem "o que cabe em 10 minutos?" — a obra em leitura pode ser a resposta.
- "Estável na sessão" virou **determinístico**: o mesmo dado no mesmo dia dá a mesma tela; só o dia
  muda o sorteio do backlog. Ler algo atualiza o Início na hora, sem "puxar para atualizar".
- Tempo por edição **medido**: mediana das edições terminadas no leitor nos últimos 90 dias, somando
  todas as sessões até a que terminou (consulta `readerCompletionTimes`); marcadas à mão não têm
  tempo e não entram. Abaixo de 3 edições não há número: os chips de 10 e 30 min não aparecem e o
  bloco diz o que os traz. A estimativa "pelas páginas" do §9.1 não foi feita: seria um segundo
  ritmo, e o medido por edição já cobre os blocos.
- Backlog: **histórias curtas primeiro** (total conhecido ≤ 6), depois "nunca começou" (sorteio do
  dia, 8 por vez), depois paradas (respeita "esconder lembretes" do Stats). Tocar numa capa do backlog
  abre a página da obra; nos outros blocos, o toque abre o leitor no capítulo.
- O destaque do Continuar lendo mostra "faltam N" (e o tempo, se medido) quando o total é conhecido,
  já que a obra não se repete no Quase terminando.
- "Continuar lendo" e "Perto de terminar" saíram da aba Stats (decisão 6); "Paradas" ficou lá.

**Testes na JVM:** `ContinueRulesTest` (10), `ReadingTimeTest` (4), `StartFeedTest` (12),
`ReadingAnalysisQueriesTest` +1 (SQLite real: sessões até a conclusão, releitura fora, marcado à mão
fora, fora do período fora). Domínio 194, dados 35, app 347, todos passando; spotless limpo.

**Emulador** (`tools/shots/home-h2-*.png`), com a biblioteca sintética `tools/make-start-test-library.py`
(16 séries, páginas e ComicInfo inventados):
- abertura a frio: Continuar lendo em **886 ms**, os outros blocos em **1236 ms** (logcat
  `StartFeed`); numa reabertura, 369 ms e 861 ms;
- Batman (2025-) passou de "Próximo: cap. 2" (já lido) para "Próximo: cap. 3" — o erro que o usuário
  relatou, corrigido sem mexer em nada;
- Filha do Amanha com #1–#6 marcados à mão: "Quase terminando · 6 de 8 · faltam 2 · ~1m no seu
  ritmo · próximo: #7" (o ritmo veio de 6 edições sintéticas lidas no leitor, 3 páginas cada: por isso
  "~1m");
- Escolha pelo tempo: sem ritmo, só "Mergulhar" + o aviso dos 3 edições; com ritmo, 10 min (1 edição
  de cada), 30 min ("2 edições" no Coração de Vidro, que só tem 2 não lidas), Mergulhar (coleção Night
  Lanterns, "3 não lidos") e Terminar algo;
- backlog: "Histórias curtas" (3, 4 e 6 edições) e "Você adicionou e nunca começou" sem repetir;
- **some e volta:** Rastro lido até o fim saiu do Continuar lendo; com a edição #4 enviada e a página
  atualizada, voltou **no topo** com "Nova edição: cap. 4";
- aba Stats sem Continuar lendo e sem Perto de terminar; nenhum crash.

**Observado, não é do Início:** a atualização da biblioteca pulou o Rastro com "nenhum lançamento era
esperado hoje" (a previsão de lançamentos do próprio Mihon); a edição nova foi trazida atualizando a
página da obra. No uso real, a atualização a traz quando o Mihon a espera.

**Não verificado no aparelho:** séries paradas (precisam de 30 dias sem leitura; cobertas por teste);
tema claro; TalkBack; tablet.

### Verificação do H3 (25/09/2026)

**Como ficou:**
- **Obras** (`start.sq`, `works`): a biblioteca e tudo o que foi lido ou aberto no leitor, não tudo o
  que já foi navegado. Por obra: capítulos, lidos, total (o confirmado; senão os capítulos, quando a
  publicação terminou), sessões, tempo ativo, última leitura e créditos.
- **Créditos com origem:** os metadados guardados (confirmado vira "seu"), mais autor, desenhista e
  gêneros da própria fonte (`ComicInference.suggest`, só o que vem com origem fonte). Inferido não conta.
- **Afinidade** (`Affinity`, domínio, puro): os pesos da §5.1 como estão lá — concluída 5, ≥ 10
  edições 4, em andamento 2, aberta uma vez 0,5, só adicionada 0,1, abandonada −0,5 —, meia-vida de 180
  dias, papel (roteiro 1,0 · personagem 0,9 · arte 0,7 · equipe 0,6 · editora/selo/universo 0,3 ·
  gênero 0,2) e origem (confirmado 1,0 · Metron/GCD/ComicInfo 0,8 · fonte 0,5 · inferido 0).
- **Escolha** (`Recommender`): candidatas são obras da biblioteca não iniciadas ou em andamento que
  não estão acima na tela; a pontuação é a afinidade do que ela compartilha com o que foi lido,
  **sem a leitura dela mesma** (uma obra nunca se recomenda), mais o hábito de formato. Sem motivo
  dizível não há recomendação, qualquer que seja a pontuação (pontuação mínima 0,3).
- **Hábito de formato:** "Você costuma terminar séries curtas: N de M com até 12 edições", só com 3
  séries curtas e fechadas começadas e 60 % terminadas.
- **Cartão:** capa, título, formato só quando conhecido ("6 edições · história fechada", "15 edições
  disponíveis"), 2 motivos, [Começar a ler] (abre o leitor no próximo capítulo) e [Por quê?]. O menu
  ⋮ e o toque longo abrem "Não tenho interesse · Já li · Agora não".
- **Por quê?:** todos os motivos (até 5), cada um com as obras por trás ("De: …", 3 nomes e "e mais
  N") e a origem do dado nesta HQ ("Nesta HQ: Fonte"), e um rodapé que diz de onde vem a conta.
- **Feedback** (`start_feedback`, migração 18): "Agora não" some por 30 dias (`LATER_DAYS`); os outros
  dois, até desfazer. Um snackbar diz o que aconteceu, com Desfazer (10 s). Uma obra recusada sai do
  Leia isso agora, da Escolha pelo tempo e do backlog — **não** do Continuar lendo nem do Quase
  terminando, que são o que o leitor já está lendo.
- **Backup:** o feedback entra no `BackupReadingStats` como campo **9** (o 8 fica para os
  acompanhamentos do H7); obras viajam por fonte + URL e são restauradas no id do aparelho novo, uma
  vez só; obra que não existe no aparelho é pulada.

**Mudou em relação ao plano:**
- **Coleções não são candidatas** do Leia isso agora: o "Mergulhar" já as oferece, e um cartão com
  uma coleção inteira não tem "próxima edição" para [Começar a ler].
- **Empate:** só o sorteio do dia (`daySeed` × obra), sem a recência de adição da §5.2 — com a
  afinidade, empate exato é raro, e a recência já pesa na afinidade.
- **"Não tenho interesse"** tira a obra; não baixa a afinidade dos autores dela (§5.1 dizia
  "negativo"). Uma recusa isolada não diz que o autor desagradou, e o leitor pode recusar o autor
  quando houver onde (H5).
- **"Já li"** só tira a obra: não marca capítulos como lidos nem conta como leitura.
- **Feedback de autor e personagem:** a tabela, a regra (tira as obras com aquele nome) e o backup
  estão prontos e testados, mas o Início ainda não tem onde recusar um autor; entra com o bloco
  "Autores e personagens" do H5.
- **Migração 18** tem só `start_feedback`; as tabelas de lançamentos, acompanhamentos e casamentos
  (§4) entram nas migrações dos seus passos.
- **Diversidade** (§5.2) não se aplica ainda: o bloco tem uma obra só.

**Testes na JVM:** `RecommenderTest` (11: cada sinal, recência, origem, a mais próxima primeiro com os
números certos, adicionar não é gostar, uma obra não se recomenda, os três feedbacks e o "agora não"
que volta, autor recusado, hábito de formato com o mínimo de 3, o hábito como motivo, obra já na tela),
`StartFeedTest` +1 (a escolha sai dos blocos de baixo; feedback tira do backlog),
`StartRepositoryImplTest` (7, SQLite real: quais obras entram, créditos com origem, total, feedback
guardado/trocado/removido, feedback por fonte + URL restaurado uma vez, obra ausente pulada,
migração 18), `BackupReadingStatsTest` +1 (ida e volta do feedback). Domínio 206, dados 42, app 348,
todos passando; spotless limpo.

**Emulador** (`tools/shots/home-h3-*.png`), na biblioteca sintética:
- primeira abertura (com a migração 18): Continuar lendo em 1328 ms, blocos `[CONTINUE, READ_NOW,
  NEAR_FINISH, BY_TIME, BACKLOG]` em 2475 ms; aberturas a frio seguintes:
  Continuar lendo em 895–1003 ms (como no H2) e os blocos em 1555–1864 ms — uns 400 ms a mais que no H2
  (1236 ms), a consulta das obras e a afinidade. O Continuar lendo continua chegando antes e não espera
  por eles;
- **escolha 1** — "Teste - Mare Vermelha · 6 edições · história fechada · Você leu 1 HQ de Ana
  Torres · Você leu 1 HQ desenhada por Davi Prado". O Por quê? mostrou 3 motivos, "De: Teste - Rastro",
  "Nesta HQ: Fonte" e "Você leu 2 HQs de Teste" (o gênero, de Rastro e Filha do Amanha) — o que a
  biblioteca montada dizia, com os créditos que as fontes tinham até ali;
- **metadados mudam a escolha:** Coração de Vidro e as candidatas não tinham autor, porque as páginas
  nunca tinham sido atualizadas (o ComicInfo só é lido ao atualizar). Atualizadas, a escolha passou a
  "Teste - Guardioes do Norte · 15 edições disponíveis · Você leu 2 HQs de Carla Nunes · Você leu 1 HQ
  desenhada por Ines Rocha" — a prevista para o histórico montado (Filha do Amanha concluída e Coração
  de Vidro em andamento, ambas de Carla Nunes). É a cobertura de metadados da §5.4 na prática;
- **feedback:** ⋮ → "Agora não" em Guardioes: snackbar "Teste - Guardioes do Norte volta em 30 dias ·
  Desfazer" e a escolha seguinte (Mare Vermelha) na hora. "Agora não" + Desfazer em Mare Vermelha:
  outra obra (Lanterna Noturna) e, desfeito, Mare Vermelha de volta. Guardioes, ainda recusada, não
  aparece no backlog ("Você adicionou e nunca começou" com as outras 8);
- **Começar a ler** abriu o leitor em Mare Vermelha #1, página 1 de 3; de volta, ela estava no topo do
  Continuar lendo ("0 de 6 · faltam 6") e o Leia isso agora tinha passado para Lanterna Noturna;
- nenhum crash.

**Observado:** no emulador, Guardioes do Norte ficou em "Agora não" (o primeiro Desfazer foi tocado
depois dos 10 s do snackbar): volta sozinha em 30 dias, ou limpando os dados do app.

**Não verificado no aparelho:** a restauração do feedback de um backup (coberta por teste, com SQLite
real); obra abandonada (precisa de 60 dias parada; coberta por teste); tema claro; TalkBack; tablet.

### Verificação do H4 (25/09/2026)

**Como ficou:**
- **Fontes das recomendações** (menu ⋮ do Início): a lista em ordem, com "Principal" e "Reserva N";
  tocar numa fonte de "Outras fontes" a põe no fim da lista, o ✕ a tira, e a alça arrasta para
  reordenar. Só fontes online que buscam; a fonte local fica de fora (o que está nela já é do leitor).
  Vazia por padrão. "Copiar a ordem da migração" aparece no menu quando a migração tem uma ordem.
- **Procurar recomendações nas fontes** (ligado por padrão): desligado, o Início não procura nada
  sozinho. A busca de teste, feita à mão, não depende dele.
- **Casamento estrito** (`TitleMatch`, domínio, puro): nome igual depois de tirar o ano entre
  parênteses, "Vol. N", pontuação, acentos e artigo inicial; ano igual quando os dois lados têm;
  quase igual (≥ 0,9) só com ano igual; duas séries diferentes empatadas não são resposta ("Batman"
  sem ano, com "Batman (2016-2020)" e "Batman (2025-)" na fonte). Só a primeira página de resultados
  de cada fonte é olhada. O ano só é lido entre parênteses ou colchetes.
- **Resolvedor** (`SourceResolver`, domínio, puro): uma série por vez, em fila; a principal
  primeiro, a próxima só sem resultado, sem a série entre os resultados, ou com falha. Achada: 7
  dias; não achada: 1 dia; achada só depois de uma falha da principal: 1 dia, para a principal ser
  tentada de novo logo; falha sem achado: nada guardado. A resposta guardada vale para a mesma ordem
  até a fonte que achou (uma reserva nova depois dela não invalida). Fonte que falhou não é
  perguntada de novo por 30 minutos. No máximo 10 séries vão à rede por sessão (`LIMITED`); o cache
  não conta.
- **`SourceFinder`** (app): busca nas fontes do Mihon (40 s por fonte, mais que os 30 s que o
  desafio do Cloudflare recebe), classifica a falha, cria a entrada **fora da biblioteca** com
  `NetworkToLocalManga` (o mesmo de abrir uma obra pela busca; nunca favorita) e escreve no logcat
  `StartFeed source "<série>" (<ano>) <status> <fonte>: <resposta>; …`.
- **Por que a fonte falhou**, sem chutar: Cloudflare quando o interceptador do Mihon desiste do
  desafio, **ou** quando uma busca recusada com 403/429/503 é seguida de um pedido ao endereço da
  fonte, sem o interceptador (que abriria o WebView de novo), e a resposta traz
  `cf-mitigated: challenge`; "o site não respondeu" para 520–527, os códigos do próprio Cloudflare
  para um site fora do ar; erro HTTP, tempo esgotado, sem conexão, não instalada.
- **Testar uma busca**: nome e ano, cada fonte da lista perguntada do zero, sem ler nem gravar o
  cache e sem pular fonte que falhou; mostra o que cada fonte respondeu e [Abrir] a obra achada.
- **`start_source_matches`** (migração 19): o cache, fora do backup. `manga_id` vira nulo se a
  entrada for apagada (limpeza do banco do Mihon), e a entrada é refeita pela URL guardada.

**Mudou em relação ao plano:**
- A **ordem das fontes** é uma preferência (`start_recommendation_sources`), como a
  `migration_sources` da migração, e vai no backup junto com as configurações do app. O campo 10 do
  `BackupReadingStats` não foi necessário.
- **"Testar uma busca"** não estava no plano: sem o H5, é o único jeito de ver o resolvedor
  trabalhar no aparelho, e serve ao leitor para saber se a principal está respondendo.
- "Pedir a principal na primeira recomendação de fora da biblioteca" (§7.2) fica para o H5, que é
  quando essas recomendações existem. O `SourceFinder.find` (com cache, limite e entrada guardada)
  já está pronto para ele.

**Testes na JVM:** `TitleMatchTest` (9, com os títulos reais das fontes do emulador: `Absolute
Batman (2024-)`, `Batman (2025-)`, `Batman (2016-2020)`, `Flashpoint (2011)`, `Flashpoint: The World
of Flashpoint`), `SourceResolverTest` (13: a principal primeiro e só ela quando tem; reservas na
ordem; Cloudflare passa para a reserva e diz por quê; achado 7 dias; não achado 1 dia; achado depois
de falha 1 dia; falha não guarda; ordem nova pergunta de novo; reserva nova depois não invalida;
fonte que falhou pulada por 30 min; limite por sessão com o cache livre; teste à mão sem cache; sem
fontes), `SourceMatchRepositoryImplTest` (5, SQLite real: ida e volta, achado ou não; resposta nova
substitui e as vencidas saem; entrada apagada deixa o casamento; **a entrada criada fica fora da
biblioteca e a que já estava nela continua nela**; migração 19). Domínio 228, dados 47, app 348,
todos passando; spotless limpo.

**Emulador** (`tools/shots/home-h4-*.png`), com as três fontes online instaladas (Read Comics
Online, ReadAllComics, XOXO Comics), todas atrás do Cloudflare:
- ⋮ do Início → "Fontes das recomendações": lista vazia com a explicação, as três em "Outras
  fontes", "Procurar" desabilitado; escolhidas, "Principal · Reserva 1 · Reserva 2", e "Outras
  fontes" some;
- **principal falhando, achada na reserva** (primeira busca do dia): "Absolute Batman" 2024 → Read
  Comics Online *falhou: erro do site (HTTP 403)*, ReadAllComics *falhou (HTTP 522)*, XOXO Comics
  *achou "Absolute Batman"*. Do PC, `curl` mostrou que o 403 era o desafio do Cloudflare
  (`cf-mitigated: challenge`, `server: cloudflare`) e o 522 o site do ReadAllComics fora do ar —
  daí a checagem do desafio e o "o site não respondeu", feitos depois disso;
- **achada na principal:** com o desafio já passado, "Absolute Batman" 2024 → Read Comics Online
  *achou "Absolute Batman (2024-)"*, e as reservas não foram perguntadas;
- **principal sem a série:** XOXO Comics arrastada para principal, "Batman" 2025 → XOXO *36
  resultados, nenhum é esta série (o primeiro: "Batman & Robin Eternal")*, Read Comics Online *achou
  "Batman (2025-)"*;
- **principal fora do ar:** ReadAllComics arrastada para principal, "Flashpoint" 2011 → *o site não
  respondeu (HTTP error 522)*, Read Comics Online *3 resultados, nenhum é esta série (o primeiro:
  "Flashpoint Beyond (2022-)")*, XOXO Comics *achou "Flashpoint (2011)"*;
- **Abrir:** "Absolute Wonder Woman" 2024 achada no Read Comics Online abriu a página da obra com
  "Adicionar à biblioteca" — criada fora da biblioteca;
- a chave desligada e a ordem sobreviveram a sair e voltar; ✕ tirou uma fonte para "Outras fontes"
  e o toque a devolveu; nenhum crash.

**Não visto no aparelho:** o rótulo "bloqueada pelo Cloudflare" depois da checagem. Limpar os cookies
do Read Comics Online não trouxe o bloqueio de volta — o WebView do Mihon passou o desafio sozinho —
e o bloqueio não se provoca de propósito. A primeira busca mostrou a falha real (como HTTP 403, antes
da checagem existir), e a checagem se apoia no cabeçalho visto com `curl`. Também não visto: o
caminho automático (`find`, cache, limite de 10), que só o H5 chama (coberto pelos testes do
resolvedor e do banco); tema claro; TalkBack; tablet.

### Verificação do H5 (26/09/2026)

**Como ficou:**
- **Quem o Metron conhece** (`Discovery.entitiesToAsk`, domínio, puro): os 4 roteiristas, desenhistas,
  personagens ou equipes de maior afinidade (§5.1) com algo lido de fato e afinidade ≥ 1,5; nunca
  gênero nem editora, nunca um nome recusado. O Metron recebe só esses nomes e ids — nunca a
  biblioteca.
- **Nome → id:** a busca de nomes do Metron acha partes do nome ("Tom King" traz "Tom Kingsley"); vale
  só o resultado com o nome igual, e dois diferentes com o mesmo nome ("Robin (Dick Grayson)",
  "Robin (Tim Drake)" para "Robin") não são resposta.
- **Id → séries:** a lista de edições do Metron com `creator_id`/`character_id`/`team_id` e
  `store_date_range_after`, dos últimos 5 anos, até 2 páginas de 100; cada edição traz a série (id,
  nome, volume, ano). O Metron ordena por nome da série, não por data; quando a lista é cortada, o
  logcat diz ("N de M edições desde AAAA") — nada de corte silencioso.
- **Escolha** (`Discovery.picks`): sem as séries que o leitor tem ou leu (pelo nome e ano, com o
  casamento do H4), cada uma pontuada pela afinidade de cada nome que a trouxe, com os motivos em
  números; no máximo 4 por nome (diversidade). As 15 melhores têm os detalhes pedidos (total,
  terminada, formato): encadernados (TPB, capa dura, omnibus) saem; as 10 primeiras vão às fontes
  pelo `SourceFinder` do H4 (limite de 10 por sessão, cache de 7 dias).
- **Cache** (`start_catalog_cache`, migração 20, fora do backup): nome → id e detalhes da série por 30
  dias, séries de um nome por 7 dias, "não está no Metron por esse nome" por 7 dias.
- **Porque você gostou de X:** a obra gostada mais recentemente (concluída, ≥ 10 edições, ou ≥ 3
  edições em andamento) que tenha ao menos 2 obras não começadas que dividam um nome com ela — da
  biblioteca e achadas nas fontes; se a mais recente não tem, a anterior. Cada capa diz o elo
  ("Também de Geoff Johns", "Também desenhada por…", "Também com Abin Sur") e, se é de fora, "em
  Read Comics Online".
- **Autores e personagens:** o nome de maior afinidade (a mesma barra de quem o Metron conhece) que
  não seja um dos nomes de X (um nome não conduz dois blocos) e tenha algo para mostrar: "Você lê
  muito X" com 2 obras lidas ou mais, "Mais de X"/"Mais com X" com uma; o motivo com os números
  embaixo; capas com o formato quando o Metron sabe ("21 edições", "8 edições · história fechada") e
  "em <fonte>". O ⋮ tem "Não quero recomendações de X" (feedback de autor ou personagem, que no H3
  ficou sem tela), com Desfazer.
- **Capas de fora:** tocar abre a página da obra (a entrada fora da biblioteca do H4); o toque longo
  tem o mesmo menu do "Leia isso agora" (não tenho interesse · já li · agora não), pelo id da entrada.
- **Linhas no fim do Início:** sem token do Metron, "Para recomendar HQs de fora da sua biblioteca…"
  com [Adicionar o token]; com séries achadas e nenhuma fonte, "N séries dos seus autores para
  procurar: escolha a fonte principal" com [Escolher a fonte] (§7.2); e a cobertura de metadados
  (§5.4), "As recomendações melhoram com metadados: N séries sem roteirista", só quando é a maior
  parte da biblioteca, com [Abrir a Biblioteca de HQ].
- **Tempo:** a descoberta roda em segundo plano, depois da biblioteca, no escopo do Início (sair o
  cancela), e de novo quando a chave ou a ordem das fontes muda. Os blocos locais não esperam: os de
  fora entram quando chegam.
- A chave do H4 passou a "Recomendar HQs de fora da biblioteca" e cobre o Metron também.

**Mudou em relação ao plano:**
- **Casamento com um ano de diferença (H4):** nome igual com o ano a 1 de distância passou a valer,
  abaixo do nome igual com o mesmo ano. Visto no emulador: o Metron data "Justice League Unlimited"
  de 2025 (a capa), a fonte de 2024 (a venda). Dois candidatos, um ano para cada lado, continuam sem
  resposta; quase igual (≥ 0,9) continua exigindo o mesmo ano.
- **Só obras não começadas** nos dois blocos novos: o que está sendo lido fica no Continuar lendo, no
  Quase terminando e nas Paradas (no emulador, o primeiro build pôs Filha do Amanhã no "Porque você
  gostou" e o Quase terminando sumiu).
- **"Autores e personagens" escolhe antes do backlog,** embora apareça depois dele: o "Você adicionou
  e nunca começou" é um sorteio do dia e levaria as obras do autor toda vez. Exceção à regra "o
  bloco mais alto fica com ela", como a Escolha pelo tempo no H2.
- **"Autores e personagens" com uma obra lida:** a regra de 2 obras deixava sem lugar o que o Metron
  achou para quem foi lido numa obra só (Geoff Johns, no emulador, sumiu da tela quando o "Porque
  você gostou" passou para outra obra). A barra agora é a do Metron; o título diz a verdade ("Mais
  de" com uma, "Você lê muito" com duas ou mais).
- **Linhas de token e fonte principal** no fim do Início, não como blocos: são avisos, não
  recomendações. Sem token, a Home continua como antes, só com a biblioteca.
- Os blocos de fora usam os detalhes do Metron só para formato e para tirar encadernados; a capa é a
  da fonte.

**Testes na JVM:** `DiscoveryTest` (16: quem é perguntado e quem nunca; gênero nunca; nome → id exato,
com acento e ambíguo; edições → séries; encadernado; o que o leitor tem ou leu não volta, pelo nome e
ano; motivos em números e série de dois nomes primeiro; limite por nome; "Porque você gostou" com obra
de fora e o elo; obra gostada sem prateleira cede a vez; obra marcada à mão sem data; autores sem o
nome do bloco de cima e sem o que está sendo lido, antes do backlog; nome de uma obra só com o que foi
achado; autor recusado; obra de fora recusada), `TitleMatchTest` +1 (ano de diferença),
`CatalogCacheImplTest` (3, SQLite real, com a migração 20), `MetronCatalogTest` (4, respostas escritas a
partir dos serializers do Metron: busca de criador, lista de edições por criador com `count` maior que a
página, detalhe da série). Domínio 245, dados 50, app 352, todos passando; spotless limpo.

**Emulador** (`tools/shots/home-h5-*.png`), com o token do Metron do usuário já no app:
- **erro achado no aparelho:** o primeiro build derrubava o fluxo do Início ao comparar uma data
  ausente (obra marcada à mão, sem histórico) — `Long` × `Int` no comparador; corrigido, com teste;
- a biblioteca sintética só tinha nomes inventados: o Metron foi perguntado por "Carla Nunes" e não a
  conhece (0 séries), como devia;
- **descoberta real:** Flashpoint (2011), com Geoff Johns confirmado, marcado como lido (#1–#5) →
  perguntados [Geoff Johns, Abin Sur, Aquaman, Atom], 35 séries, 7 escolhidas, **achadas no Read
  Comics Online**: Geiger (2024-), Redcoat (2024-), Hyde Street (2024-), Absolute Green Lantern (2025-),
  Superman: Space Age (2022-); na segunda rodada, com a principal no desafio do Cloudflare, Justice
  League Unlimited achada na XOXO. Primeira rodada em 39 s (Metron a 20 pedidos por minuto, mais as
  buscas); as seguintes em 22–24 ms com tudo no cache, e em 30–41 s quando uma série que falhou por
  causa de uma fonte foi procurada de novo (falha não é guardada, H4);
- **Cloudflare do H4 visto nos dois caminhos:** "failed CLOUDFLARE (HTTP error 403)" pela checagem do
  desafio, e "failed CLOUDFLARE (Falha ao contornar o Cloudflare)" pelo interceptador;
- "Porque você gostou de Flashpoint (2011)": Redcoat, Geiger, Hyde Street ("Também de Geoff Johns · em
  Read Comics Online") e Absolute Green Lantern ("Também com Abin Sur"); depois de ler mais do
  sintético, "Porque você gostou de Teste - Rastro" com Eco, Lanterna Noturna e Linha de Fuga;
- "Autores e personagens · Mais de Geoff Johns · Você terminou 1 HQ de Geoff Johns" com Redcoat (21
  edições), Geiger (25) e Hyde Street (14), "em Read Comics Online";
- tocar em Redcoat abriu a página com "Adicionar à biblioteca"; toque longo em Geiger → "Agora não":
  "Geiger (2024-) volta em 30 dias", a capa saiu, Desfazer a trouxe; ⋮ → "Não quero recomendações de
  Geoff Johns": o bloco passou a "Mais com Abin Sur · Você leu 5 edições com Abin Sur", e Desfazer
  voltou a Geoff Johns;
- sem fontes escolhidas: "7 séries dos seus autores para procurar: escolha a fonte principal";
  [Escolher a fonte] abriu a tela das fontes;
- "As recomendações melhoram com metadados: 12 séries sem roteirista" com a maior parte da biblioteca
  sem roteirista; com 3 séries a mais atualizadas, deixou de ser a maioria e a linha saiu;
- nenhuma das obras achadas entrou na biblioteca (conferido na Biblioteca de HQ); nenhum crash depois
  da correção; abertura a frio: Continuar lendo 795–971 ms, blocos 1330–1517 ms (como no H4).

**Observado, não é do H5:**
- **Wolverine (2024-)** apareceu no "Você adicionou e nunca começou": já estava na biblioteca antes
  de qualquer busca do H5 (a contagem de "Não iniciadas" bate: 16 → 14 com Flashpoint e Mare lidos) e
  só não tinha saído no sorteio do dia.
- Duas capas de fora não carregaram: a imagem está atrás do Cloudflare, e o carregador de capas do
  Mihon desistiu do desafio.
- Voltar da página de uma obra põe o Início no topo: os blocos são recalculados e a lista perde a
  posição (vem do H2).

**Dados de teste mudados no emulador:** Flashpoint (2011) #1–#5 e Mare Vermelha #1 marcados como
lidos; Eco, Ferrugem e Linha de Fuga atualizadas (autor e desenhista vieram do ComicInfo); o cache do
Metron e das fontes guardado.

**Não verificado no aparelho:** a linha sem token (o emulador tem token; a tela do token mostra o token
em texto e não foi aberta); o corte de uma lista longa (Geoff Johns coube; coberto pelo log e pelo
teste de `count`); personagem ou equipe recusado pelo ⋮ (mesmo caminho do autor); tema claro;
TalkBack; tablet.

### Verificação do H6 (26/09/2026)

**Como ficou:**
- **Fonte** (decisão 5, do usuário): o **Metron**, com o token do leitor — a lista de edições filtrada
  por `store_date_range` da semana ISO (segunda a domingo), com o dia de venda e a capa de cada edição
  e a série com id, nome e ano. Sem token ou com o Metron fora, a lista semanal do **GCD**
  (`on_sale_weekly`), que diz só a semana e lista cada capa variante como uma edição. É um pedido
  genérico, igual para qualquer pessoa: nada da biblioteca sai para montá-lo.
- **Guardado no aparelho** (`release_weeks`/`release_items`, migração 21, fora do backup): a semana
  corrente é baixada de novo no máximo uma vez por dia; uma semana baixada antes de acabar é baixada
  uma última vez depois; uma semana baixada depois de acabar nunca mais. No máximo 10 páginas por
  semana (1.000 no Metron, 500 no GCD), e o logcat diz quando uma lista é cortada.
- **Uma entrada por edição** (`Releases.grouped`): a variante do GCD vai para a capa regular; se a
  regular não está na lista, a primeira variante fica no lugar dela. O número sai do `descriptor`
  ("24 [Clay Mann Cardstock Variant Cover]" é a 24), a série e o ano do nome ("Absolute Batman (2024
  series)").
- **O que importa ao leitor** (`Releases.relevant`, domínio, puro): primeiro as séries que ele tem ou
  leu — pelo id do catálogo quando os metadados o têm (`METRON_ID`/`GCD_ID` da série, nunca o da
  edição), senão pelo nome e ano com o casamento do H4 —, com "Porque você lê Absolute Batman
  (2024-)"; depois as séries que o Metron listou no H5 para um nome que ele lê, com "De Geoff Johns,
  que você leu" / "Arte de …" / "Com Abin Sur, que você lê". Nada recusado volta.
- **Bloco "Lançamentos da semana"** no Início (entre Leia isso agora e Porque você gostou): até 8
  capas com número, dia ("qua., 23/09") e motivo, e "Ver todos". Só aparece com algo que importa; as
  edições não entram na regra "uma obra uma vez" (uma edição nova de uma série em leitura é notícia,
  não repetição).
- **Tela "Lançamentos"** (Ver todos, e também o ⋮ do Início): uma semana por vez com ◀ ▶ e "Esta
  semana"; "Para você (N)" com os motivos; o resto por dia, recolhido ("quarta-feira, 23 set. · 75
  lançamentos · Mostrar"); filtro "Só o que importa para você"; sem conexão, "Sem conexão: mostrando a
  lista de <data>"; crédito da fonte no rodapé ("Dados: Metron", ou o GCD com a licença CC BY-SA 4.0 e
  "O GCD diz a semana, não o dia").
- **Tocar num lançamento:** de uma série do leitor abre a obra dele; de fora, procura a série nas
  fontes (H4) numa busca pedida pelo toque — sem o limite de 10 por sessão, que é para o que o Início
  faz sozinho — e abre a página, ou diz "Não achada nas suas fontes".

**Mudou em relação ao plano:**
- **Oito blocos:** com os lançamentos, o Início tem os 8 tipos da V1 e o máximo continua 7; quando
  todos têm conteúdo, "Escolha pelo tempo" sai primeiro (é uma ferramenta, sem conteúdo até tocar num
  chip).
- **Lançamentos no ⋮ do Início:** sem nada relevante na semana o bloco não aparece, e o "Ver todos"
  some com ele; o calendário precisa de outra porta (achado no emulador).
- **Relevância por autor e personagem** usa as respostas do Metron do H5 (as séries de cada nome), não
  o detalhe de cada edição: nenhum pedido a mais. "#1 nova de editora ou personagem recorrente" (§8.2)
  não foi feito — exigiria o detalhe de centenas de edições por semana.
- **O sino** fica para o H7, como no plano.
- A tabela `release_details` do §4 não foi necessária: o Metron já dá dia e capa na lista.

**Testes na JVM:** `ReleasesTest` (10: semana ISO, inclusive na virada do ano; edição com variantes é
uma; variantes sem a regular; Absolute Batman #24 uma vez, "porque você lê"; id do catálogo antes do
nome e ano; nome que o leitor lê depois das séries dele; nada recusado; quando baixar de novo; o bloco
no Início; com 8 blocos, a Escolha pelo tempo sai primeiro), `SourceResolverTest` +1 (busca pedida
pelo toque sem o limite da sessão), `ReleaseRepositoryImplTest` (4, SQLite real: a semana guardada e
trocada, semanas separadas e semana vazia, **o id do catálogo da série chega à obra e o da edição
não**, migração 21), `ReleaseMappingTest` (4: lista do Metron e do GCD escritas a partir dos schemas;
Absolute Batman #24 uma vez pelas duas; o `METRON_ID` casa mesmo com títulos diferentes). Domínio
256, dados 54, app 356, todos passando; spotless limpo.

**Emulador** (`tools/shots/home-h6-*.png`), com o token do Metron do usuário:
- semana 39 de 2026 baixada do Metron: **79 edições em 1,4 s** (o GCD lista 273 para a mesma semana,
  com as variantes); semana 40, pedida na tela: 66 edições em 1,0 s; voltar à semana 39 não pediu
  nada (guardada);
- **"Lançamentos da semana"**: Absolute Batman #24 ("qua., 23/09 · Porque você lê Absolute Batman
  (2024-)") **uma vez**, Wolverine #28 ("Porque você lê Wolverine (2024-)") e Justice League Unlimited
  #23 ("Com Abin Sur, que você lê", das respostas do Metron do H5), com as capas do Metron;
- **tela Lançamentos**, 21–27 set.: "Para você (3)", "terça-feira, 22 set. · 1 lançamento",
  "quarta-feira, 23 set. · 75 lançamentos"; semana 40: Geiger #25 e Hyde Street #13 ("De Geoff Johns,
  que você leu"); "Mostrar" abriu o dia, "Só o que importa" deixou só os 2, "Esta semana" voltou;
- **toques:** Absolute Batman #24 abriu a obra da biblioteca; Justice League Unlimited #23 abriu a
  página na XOXO, fora da biblioteca (do cache do H5); 2000 AD #2501 e A Mischief of Magpies #2
  achadas na XOXO; Any Given Smile #1 → "Não achada nas suas fontes";
- abertura a frio: Continuar lendo em 922 ms, como antes; os lançamentos chegam depois, da rede;
  nenhum crash.

**Não verificado no aparelho:** a lista do GCD (o emulador tem token e o Metron respondeu; coberta
pelos testes com a forma da resposta do GCD); "Sem conexão: mostrando a lista de …"; a virada do ano;
tema claro; TalkBack; tablet.

**Observado:** quando o Read Comics Online está na pausa de 30 minutos depois de falhar no Cloudflare,
"Não achada nas suas fontes" também vale para "não perguntada"; a mensagem não distingue.

### Verificação do H7 (26/09/2026)

**Como ficou:**
- **O sino** em cada lançamento (bloco do Início e tela Lançamentos): cheio quando algo acompanhado
  cobre a edição. Tocado, pergunta o que acompanhar — "Só esta edição", "Todos os próximos números" e,
  com os detalhes da edição no Metron (um pedido), os roteiristas e desenhistas (até 3) e os
  personagens (até 5), pelos ids do Metron. Numa lista do GCD, só os dois primeiros, e o diálogo diz
  por quê.
- **O que é acompanhado** (`release_follows`, migração 22) tem a chave do catálogo — a edição, a
  série, o personagem ou o autor —, então vai no backup como está (`BackupReadingStats`, **campo 8**,
  o reservado desde o H3).
- **A checagem** (`ReleaseAlerts`, a mesma do job e do "Verificar agora"): baixa a semana (H6), pede
  ao Metron as edições da semana de cada autor e personagem acompanhado (um pedido cada, até 10), e
  avisa **"Absolute Batman #24 saiu esta semana"** do que está à venda até hoje; depois procura a
  edição avisada entre os capítulos — na biblioteca, os que a atualização do Mihon trouxe (nada é
  buscado para ela); fora, a série é achada nas fontes (H4) e os capítulos trazidos como ao abrir a
  página (até 5 por checagem) — e avisa **"já está disponível · em Read Comics Online · Ler agora"**,
  que abre o leitor no capítulo. A edição é o capítulo com o número dela.
- **Uma vez só** (`release_notified`, chave da edição × tipo de aviso): uma edição da lista da semana e
  da de um autor acompanhado é um lançamento só; quem cobre qual não importa.
- **Avisos desligados:** sem a permissão de notificação (Android 13+) ou com o canal desligado, a
  checagem não faz nada e **não marca nada** — os avisos ficam para quando forem ligados. A tela
  Alertas diz "Os avisos estão desligados neste aparelho" com [Permitir] (o pedido do Android, ou as
  configurações quando ele não pode mais pedir).
- **Job diário** (`ReleaseAlertJob`, WorkManager, com conexão): agendado no primeiro acompanhamento,
  cancelado quando não sobra nenhum, reagendado ao abrir o Início (depois de uma restauração, por
  exemplo). As dependências vêm de uma interface do pacote contribuída ao grafo (`@ContributesTo`):
  `AppGraph.kt` não mudou.
- **Canal "Lançamentos"** criado pelo pacote; os canais do Mihon ficam como estão. "Saiu" abre a obra
  da biblioteca quando há uma (o mesmo intent das notificações do Mihon); senão, o app.
- **Tela "Alertas"** (⋮ do Início): o que é acompanhado, com ✕; "Verificar agora" e o que ela avisou.

**Mudou em relação ao plano:**
- A **tabela `release_notified`** guarda, além da chave, a série e o número: o "já disponível" é
  procurado por até 30 dias depois do "saiu", sem precisar da lista da semana.
- Chaves do catálogo em vez de ids do GCD (§4): a fonte principal dos lançamentos é o Metron (H6).
- **Personagem e autor** casam pela lista de edições do Metron com o filtro do id, não pelo detalhe
  de cada edição (§8.3): um pedido por acompanhamento por dia, e funciona para qualquer edição.
- "Verificar agora" **não estava no plano**: é o jeito de conferir no aparelho sem esperar o job, e a
  checagem é a mesma do job.
- Ícone do sino: `notifications` do Material Symbols (contorno e cheio), como o `home` do H1.

**Testes na JVM:** `AlertsTest` (9, com a data e o relógio dados: o que cada acompanhamento cobre;
série acompanhada no Metron coberta na lista do GCD; o que saiu até o dia, e o que ainda não; nunca
duas vezes, com dois acompanhamentos cobrindo; **edição na lista da semana e na de um autor, avisada
uma vez**; variantes uma vez; procurar o capítulo por um mês até o "disponível"; o capítulo pelo
número; o sino), `AlertRepositoryImplTest` (3, SQLite real: acompanhamentos, uma vez por tipo de aviso,
migração 22), `BackupReadingStatsTest` +1 (acompanhamentos numa ida e volta). Domínio 265, dados
57, app 357, todos passando; spotless limpo.

**Emulador** (`tools/shots/home-h7-*.png`), Android 16, com o token do Metron:
- o sino nas capas do bloco; tocado em Absolute Batman #24, o diálogo trouxe do Metron Nick Dragotta
  (arte), Scott Snyder (roteiro) e 5 personagens "(Earth Alpha)"; "Todos os próximos números" + Scott
  Snyder salvos: sino cheio, "2 follows, daily check scheduled";
- **sem permissão de notificação** (o emulador não a tinha): o job rodou ao ser agendado e o
  "Verificar agora" também — "notifications are off, nothing checked" — e a tela disse "Nada
  verificado: os avisos estão desligados…"; nada marcado;
- com a permissão dada (pelo [Permitir] da tela e o diálogo do Android, com o OK do usuário):
  "Verificar agora" → as notificações **"Absolute Batman #24 saiu esta semana"** e **"Absolute Batman
  #24 já está disponível · em Read Comics Online · Ler agora"**; "Ler agora" abriu o leitor em
  **Absolute Batman (2024-) #24** (28 páginas);
- **achado no aparelho:** o log disse "out [Absolute Batman #24, Absolute Batman #24]" — a edição veio
  da lista da semana e da de Scott Snyder. Na tela eram duas notificações (a repetida tinha o mesmo
  id), mas a regra "uma vez" tem que valer na lógica: corrigido (`Releases.grouped` junta pela chave),
  com teste;
- "Verificar agora" de novo: **"Nada novo."** (out [], available []);
- ✕ nos dois: "1 follows, daily check scheduled" → **"0 follows, daily check cancelled"**, "O que você
  acompanha (0)"; nenhum crash.

**Não verificado no aparelho:** o job disparando sozinho depois de 24 h (o WorkManager o rodou ao
agendar, com a mesma checagem); o "já disponível" de uma série fora da biblioteca (caminho do H4 +
`UpdateMangaFromRemote`, o mesmo de abrir a página; coberto pela regra do capítulo nos testes);
acompanhar personagem até sair uma edição dele; a restauração dos acompanhamentos (coberta pelo teste
do backup); tema claro; TalkBack; tablet.

**Dados de teste mudados no emulador:** a permissão de notificação do Mihon dada (com o OK do
usuário); Absolute Batman #24 aberto pelo "Ler agora" (está no Continuar lendo).

### Verificação do H8 (03/10/2026)

**Como ficou:**
- **"Arcos e eventos"** (⋮ do Início): busca pelo nome no Metron (`arc/?name=`); tocar num resultado
  abre a página do arco. Sem token do Metron, a tela diz que ele é preciso.
- **O arco** (`ArcCatalog`): o detalhe e a lista de edições do Metron (`arc/{id}/issue_list/`, até 5
  páginas), guardados em `start_arcs`/`start_arc_issues` (migração 23) por 7 dias. Abrir de novo nesse
  prazo não pede nada; com o Metron fora do ar, mostra o guardado e avisa. A ordem é a do Metron (data
  de capa, série, número); edição sem série fica de fora.
- **De onde cada série é lida** (`ArcReading`): a série que você tem (na biblioteca ou já lida) fica
  na entrada que é sua, sem busca; as outras são procuradas uma vez cada, em fila, pelo H4 (principal
  primeiro), e aparecem aos poucos ("Procurando nas suas fontes: 5 de 11 séries"). Achada a série, os
  capítulos vêm da fonte como ao abrir a página quando falta uma edição que já saiu. Nada vai para a
  biblioteca.
- **A edição é o capítulo com o número dela**; um one-shot (#1) é também o único capítulo da entrada
  quando ele não tem número — as fontes guardam um one-shot como "Full", número -1.
- **O rastreador** (`Arcs`, puro): "1 de 33 · 3% · faltam 32" e a barra; o total é o arco inteiro, com
  as edições que ainda não saíram e as que nenhuma fonte tem (decisão 15). Os capítulos são lidos ao
  vivo: ler uma edição move o arco sem recarregar a página.
- **"Começar/Continuar o arco: <série> #N"** abre a primeira edição disponível não lida, na ordem do
  arco; cada linha também abre a sua no leitor. Estados na linha: lida · em <fonte> · procurando… ·
  não achada nas suas fontes · ainda não saiu.

**Mudou em relação ao plano:**
- **A porta provisória** é uma busca pelo nome (⋮ do Início), não "os arcos de um lançamento": a lista
  de lançamentos do Metron não traz o arco de cada edição (seria um pedido de detalhe por lançamento),
  e a busca serve para qualquer arco, não só para os da semana. O bloco do Início continua no H9.
- O casamento do one-shot ("Full", -1) não estava no plano: apareceu no emulador (abaixo).

**Testes na JVM:** `ArcsTest` (9: estado de cada edição pela entrada da série; progresso do arco
inteiro, com edições que não saíram e não achadas; a próxima é a primeira não lida que abre, na
ordem; séries sendo procuradas; edição que saiu e a entrada não tem; a edição pelo número; **o
one-shot pelo único capítulo sem número**; arco vazio sem porcentagem; a ordem pela posição),
`ArcRepositoryImplTest` (2, SQLite real: guardar e trocar as edições; migração 23), `MetronArcTest`
(2, respostas escritas pelo formato do Metron: o arco; as edições na ordem, sem a que não tem série).
Domínio 274, dados 59, app 359, todos passando; spotless limpo.

**Emulador** (`tools/shots/home-h8-*.png`), Android 16, com o token do Metron:
- ⋮ do Início → "Arcos e eventos" → "zoo": "Nenhum arco ou evento com esse nome no Metron" (o pedido
  voltou 200, vazio); "absolute power": **Absolute Power** e Absolute Power (Superman / Batman);
- **Absolute Power**: imagem, "33 edições", a descrição; "0 de 33 · 0% · faltam 33"; "Procurando nas
  suas fontes: 5 de 11 séries" e as linhas passando de "Procurando…" para "em XOXO Comics" uma a uma; a
  lista inteira na ordem do Metron (FCBD Special Edition, Ground Zero, Green Arrow #13, Absolute Power
  #1 … Absolute Power #4) e "Dados: Metron (metron.cloud)." no fim; log "StartFeed arc Absolute Power:
  33 issues from Metron";
- **achado no aparelho:** Ground Zero #1 dizia "Não achada nas suas fontes" com a série achada no XOXO
  — a entrada tinha um capítulo só, "Full", número -1. Corrigido em `Arcs.chapterOf`, com teste;
  depois: "em XOXO Comics", e o botão passou a "Continuar o arco: Absolute Power: Ground Zero #1";
- "Começar o arco: Green Arrow #13" abriu o leitor em **Green Arrow (2023) · Issue #13**; lida até a
  página 25 e de volta: **"1 de 33 · 3% · faltam 32"**, a barra andou, Green Arrow #13 "Lida", o botão
  "Continuar o arco: Absolute Power #1" — sem recarregar;
- tocar na linha de Ground Zero abriu o leitor em **Absolute Power: Ground Zero · Full** (44 páginas);
- reabrir o arco depois de reinstalar: nenhum pedido do arco ao Metron (o guardado), o progresso igual;
  Green Arrow não foi procurada de novo (já é sua, lida); nenhum crash.

**Limite visto (do H4, não do arco):** Batman (2016), Superman (2023), Wonder Woman (2023) e Green
Lantern (2023) — 10 das 33 edições — ficaram "Não achada": a busca manda só o nome, e no XOXO "Batman"
traz 36 títulos parecidos sem o certo ("Batman & Robin Eternal", …). Nomes genéricos são justamente as
séries principais dos eventos. Proposta à parte: quando a busca pelo nome traz resultados mas nenhum
casa, tentar de novo com o ano ("Batman 2016").

**Não verificado no aparelho:** arco com série da biblioteca em outra fonte (o caminho do dono é o
do H6/H7, coberto pelos testes); Metron fora do ar com o arco guardado; arco maior que 5 páginas
(250 edições); tema claro; TalkBack; tablet.

**Dados de teste mudados no emulador:** o arco Absolute Power guardado; entradas fora da biblioteca
criadas pela busca (Ground Zero, Green Arrow (2023), Absolute Power, Origins, Task Force VII, Super
Son) com capítulos; Green Arrow #13 lida (Green Arrow (2023) está no Continuar lendo) e Ground Zero
aberto na página 1.

### Verificação do H9 (03/10/2026)

**Como ficou:**
- **Bloco "Arcos e eventos"** na ordem fixa logo depois de "Lançamentos da semana" (`StartBlockKind.ARCS`),
  com até 6 arcos numa fileira e [Procurar] (a busca do H8). Com 9 tipos de bloco, o corte é o do H6:
  "Escolha pelo tempo" sai primeiro, depois os de baixo.
- **Cartão:** a imagem do arco, o nome, "1 de 33 · faltam 32" com a barra quando há edição lida (senão
  "33 edições"), "<série> #N saiu esta semana" quando uma edição dele que importa saiu, e o motivo
  ("Porque você lê Absolute Batman", ou o do lançamento: "De Geoff Johns, que você leu"). Tocar abre a
  página do arco; segurar oferece **"Não tenho interesse neste arco"**, com "Desfazer".
- **De onde vêm** (`ArcShelf.leads`, puro), nesta ordem, cada arco uma vez, com o motivo mais alto:
  1. **os arcos que você está lendo** — guardados no aparelho (H8), com edição lida e alguma faltando,
     o lido mais recentemente primeiro. Fica no lugar dos "seguidos" até o H10;
  2. **os do que você abriu nos últimos 60 dias** — o arco nos metadados confirmados do capítulo
     (`ComicField.ARC`), o mais recente primeiro; o nome vira o id pelo Metron (`arc/?name=`, nome
     exato e único; guardado 30 dias, ou 7 quando não acha), até 5 nomes;
  3. **os dos lançamentos da semana que importam** (`Releases.relevant`, o mesmo do bloco de cima): o
     detalhe da edição no Metron traz os arcos (guardados 7 dias), até 5 lançamentos.
  Fora: o arco recusado (enquanto valer), o de uma obra recusada, e o **lido até o fim**.
- **Progresso no cartão** (`ArcShelf.progress`): o mesmo rastreador do H8, contando as obras que você
  tem (biblioteca ou lidas); série que você não tem conta como não lida. Nada é procurado nas fontes
  para o Início — a página do arco procura o resto.
- **Rede** (`ArcShelfSource`): primeiro o que está guardado (sem rede), depois o Metron para o que
  falta; tudo guardado (`start_catalog_cache` e `start_arcs`), então reabrir o Início não pede de novo.
  Sair do Início cancela (o fluxo é o do feed, `WhileSubscribed`). Sem token do Metron, só o guardado.
- **Recusar um arco** é `FeedbackSubject.ARC` (id 3) com a chave do arco; vai no backup como as recusas
  de nomes (o campo 9 já carrega o tipo), e não conta como nome recusado (`Recommender.unwantedNames`).

**Mudou em relação ao plano:**
- **(1) "arcos seguidos em andamento"** virou **"arcos que você está lendo"** até o H10: seguir é do
  H10, e o arco aberto pela página do H8 e lido em parte é justamente o que você está lendo — sem isso,
  ele sumiria do Início (as entradas trazidas pela página não têm metadados com o arco).
- **Sugestão do usuário (League of Comic Geeks):** não usada. O site responde 403 a acesso automático
  até no `robots.txt` (03/10/2026); ler a lista de eventos de lá exigiria contornar essa proteção. Os
  arcos continuam vindo só do Metron.

**Testes na JVM:** `ArcShelfTest` (9: a ordem lendo → do que você lê → da semana; um arco uma vez, com
o motivo mais alto e a edição da semana; **nada recusado** — arco recusado enquanto vale, obra
recusada; arco recusado não é nome recusado; progresso pelas obras que você tem; "está lendo" pela
última leitura de uma obra com edição lida no arco; lido até o fim não entra; o bloco depois de
Lançamentos e no máximo 6; a série casada como num lançamento), `ArcRepositoryImplTest` +2 (os arcos
guardados com as suas edições; os nomes de arco do que foi aberto, o mais recente primeiro, uma vez,
só confirmados, com o limite). Domínio 283, dados 61, app 359, todos passando; spotless limpo.

**Emulador** (`tools/shots/home-h9-*.png`), Android 16, com o token do Metron:
- Início: depois de "Lançamentos da semana" (Geiger #25, Hyde Street #13), **"Arcos e eventos"** com
  [Procurar] e o cartão **Absolute Power — "1 de 33 · faltam 32"** com a barra (o arco do teste do H8,
  com Green Arrow #13 lida); log "StartFeed arcs: 1 started, 0 from reading, 0 from releases ->
  [Absolute Power]";
- **dos lançamentos:** o Metron foi perguntado pelos dois da semana (`issue/172633/`, `issue/163765/`) —
  nenhum está num arco; reabrindo, não perguntou de novo (guardado);
- **do que você lê:** aberta a Issue #1 de "Stats ComicInfo Test" (arco "Night Lanterns", sintético,
  nos metadados confirmados), o Início perguntou `arc/?name=Night Lanterns` (200) e, sem arco com esse
  nome no Metron, não acrescentou nada — o caminho roda até o Metron; um arco real não pôde ser visto
  por aqui (abaixo);
- tocar no cartão abriu a página do arco com o mesmo "1 de 33"; [Procurar] abriu a busca;
- segurar → "Não tenho interesse neste arco" → o bloco sumiu (era o único) e o aviso "O arco Absolute
  Power não será mais sugerido · Desfazer"; Desfazer → o cartão voltou; nenhum crash.

**Não verificado no aparelho:** um arco vindo de verdade dos metadados (nenhuma HQ do emulador tem o
arco de um arco real nos metadados — o pedido de metadados automáticos de 03/10 mudaria isso) ou de um
lançamento da semana (nenhum dos desta semana está num arco); vários cartões na fileira; "saiu esta
semana" no cartão; tema claro; TalkBack; tablet.

**Visto de passagem (fora do H9):** "Continuar lendo" mostra "Cap. -1" para um one-shot (o capítulo
"Full" sem número).

**Pedido registrado em 03/10/2026, a planejar como passo próprio:** "Qualquer HQ que eu adicionar à
biblioteca ou ler, o app pega os metadados automaticamente" — hoje é pela tela de metadados, à mão.

**Dados de teste mudados no emulador:** a Issue #1 de "Stats ComicInfo Test" aberta (está no Continuar
lendo); o arco Absolute Power recusado e desfeito; respostas do Metron guardadas.

