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
5. **Fonte de lançamentos:** com o token do Metron (25/09/2026), Metron como fonte principal dos
   lançamentos (semanas de antecedência, autor e personagem em cada edição) e GCD quando o Metron
   falhar? (sim; decidir antes do H6)
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

---

## Status

- [x] H1 navegação — `StartTab`, `StartNavigation`, `StartRoutes`, `MoreUpdatesScreen`/`MoreBrowseScreen`,
  `ContinueReadingSource` (Continuar lendo compartilhado com a aba Stats)
- [ ] H2 esqueleto do Início (continuar, quase terminando, backlog, tempo) + seletor de blocos
- [ ] H3 motor de afinidade, "Leia isso agora", "Por quê?", feedback
- [ ] H4 fonte principal e busca nas fontes
- [ ] H5 descoberta pelo catálogo (Metron): "Porque você gostou de X", autores e personagens
- [ ] H6 lançamentos da semana + calendário
- [ ] H7 alertas e notificações

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
