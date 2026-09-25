# Plano — Histórico e Stats de leitura (fork Mihon)

> Plano de implementação do documento `Plano_Stats_Mihon_Claude_Code.md`, ancorado no código real
> deste fork (branch `feature/bubble-zoom`, HEAD `56323b136`). Levantado em 24/09/2026.
>
> **Como usar:** cada passo abaixo (A1, A2, …) é **uma sessão**, como as fases do Bubble Zoom.
> Uma sessão implementa um passo, verifica, atualiza a seção *Status* no fim deste arquivo e para.

---

## 0. Resumo em uma tela

- **O que existe hoje não mede leitura.** O Mihon guarda, por capítulo, a *última* vez que foi
  aberto (`history.last_read`) e um tempo acumulado (`history.time_read`) que é **tempo de relógio
  entre `onResume` e `onPause` do leitor** — inclui carregamento, pausas e o aparelho largado
  aberto, e cai inteiro no dia em que o leitor fechou. Não há sessões, nem páginas por sessão, nem
  total de páginas guardado. Esse dado legado vira **"importado, aproximado"**, nunca "tempo ativo".
- **Três tabelas novas resolvem a Fase A:** `reading_sessions` (uma linha por sessão, gravada de
  forma incremental), `reading_day_segments` (a sessão fatiada por dia civil **no momento em que
  acontece**, que é o que torna meia-noite e troca de fuso corretos e baratos de agregar) e
  `chapter_reading_progress` (a "marca d'água" por capítulo, que distingue página nova de
  releitura e conta cada conclusão uma vez só).
- **O leitor ganha um rastreador**, uma máquina de estados pura (testável na JVM, relógio
  injetado) que só conta tempo quando o leitor está em primeiro plano, com foco, com a página
  carregada e com interação recente. O código upstream recebe só chamadas de uma linha.
- **A aba Histórico vira [Stats | Histórico]**, com Stats como padrão, usando o `TabbedScreen` que
  a aba Navegar já usa. A lista cronológica atual fica intacta.
- **Backup:** um campo protobuf novo e opcional (`Backup` 9001) com sessões e progresso.
  Backups antigos continuam restaurando igual; restaurar duas vezes não duplica sessões.
- **Metadados de HQ (Fase C)** ficam em tabelas próprias, editáveis, com proveniência
  (`confirmado` / `Metron` / `GCD` / `ComicInfo` / `fonte` / `inferido`), nunca preenchidos só a
  partir de nome de arquivo. O Metron é consultado só quando o usuário pede, uma série por vez, com o
  token dele; sem token, ou quando o Metron falha ou não acha, a busca vai ao Grand Comics Database.
- **Nada sai do aparelho.** Nenhum evento novo passa pelo módulo `telemetry` (que tem uma variante
  Firebase); modo anônimo não grava sessão.

---

## 1. O que o código tem hoje

### 1.1 Arquitetura

| Camada | Onde | Convenção |
|---|---|---|
| Banco | `data/src/main/sqldelight/tachiyomi/data/*.sq`, views em `…/view/*.sq` | SQLDelight 2.3, dialeto SQLite 3.38, `generateAsync = true` |
| Migrations | `data/src/main/sqldelight/tachiyomi/migrations/1..14.sqm` | próxima é **15.sqm** |
| Repositórios | `data/src/main/java/tachiyomi/data/<área>/…RepositoryImpl.kt` | `@Inject @SingleIn(AppScope) @ContributesBinding(AppScope)` (DI **Metro**) |
| Domínio | `domain/src/main/java/tachiyomi/domain/<área>/{model,repository,interactor}` | Kotlin puro; testes JUnit 6 + kotest + mockk |
| Telas | `app/…/ui/<área>/<X>Tab.kt` + `<X>ViewModel.kt`; composables em `app/…/presentation/<área>/` | Voyager (abas/navegação) + `ViewModel` AndroidX via `metroViewModel<>()` |
| Abas | `ui/home/HomeScreen.kt`: Biblioteca, Novidades, **Histórico**, Navegar, Mais | `TabbedScreen` (`presentation/components/TabbedScreen.kt`) já usado em `BrowseTab` |
| Strings | `i18n/src/commonMain/moko-resources/base/strings.xml` (+ `pt-rBR`) | `MR.strings.*` |
| Backup | `app/…/data/backup/{models,create,restore}` | protobuf `kotlinx.serialization`, `@ProtoNumber`, `BackupOptions`/`RestoreOptions` |
| Estatísticas atuais | `ui/stats/StatsViewModel.kt` + `presentation/more/stats/` (Mais → Estatísticas) | só agregados da biblioteca + `sum(time_read)` |

### 1.2 Os dados que já existem, e quanto valem

| Dado | Onde nasce | Confiável para | Não serve para |
|---|---|---|---|
| `history.last_read` (1 linha por capítulo, `UNIQUE chapter_id`) | `ReaderViewModel.updateHistory()` em `onPause` e troca de capítulo | "este capítulo foi aberto por último em X" | contar leituras ou dias: reler **sobrescreve** a data |
| `history.time_read` (acumulado) | mesmo lugar: `agora − restartReadTimer()` (`onResume`, troca de capítulo) | ordem de grandeza total ("~40 h no Mihon") | tempo ativo, tempo por dia, sessões: inclui carregamento, ociosidade, e cai todo no dia do fechamento |
| `chapters.read` | fim do capítulo no leitor (`updateChapterProgressOnComplete`) **ou** marcação manual (`SetReadStatus`) **ou** "marcar duplicados" **ou** backup | estado "lido" | quando foi lido, nem por quem (leitor/manual) |
| `chapters.last_page_read` | `updateChapterProgress` a cada página | onde retomar | páginas lidas: é **sobrescrito ao voltar** páginas |
| total de páginas do capítulo | só em memória (`ReaderChapter.pages`) depois de carregar | — | **não é persistido** em lugar nenhum |
| Tela Histórico (`historyView.sq`) | `history` + `chapters` + `mangas` | lista "último capítulo por obra" | é **uma linha por obra**, não um diário cronológico |
| Metadados de obra (`mangas.author/artist/genre/status`) | extensão/fonte | exibir | classificação de HQ: autor e artista vêm como texto livre, sem editora, tipo de unidade, run |
| `ComicInfo.xml` (`core-metadata`) | fonte local | série, número, roteirista, desenhista, colorista, ano, gênero | editora, volume, total de issues, personagens, arco (não estão no modelo) |

**Consequência:** nada do que existe permite, retroativamente, dizer quanto se leu num dia. Antes
da instrumentação, tempo e páginas são **indisponíveis**; conclusões legadas só entram marcadas
como importadas.

### 1.3 Pontos de gancho no leitor

| Evento | Onde hoje |
|---|---|
| página exibida | `ReaderViewModel.onPageSelected(page)` (ignora `InsertPage`, a metade de página dupla) |
| capítulo muda dentro do leitor | `ReaderViewModel.loadNewChapter` / `loadAdjacent` |
| leitor em primeiro/segundo plano | `ReaderActivity.onResume` / `onPause` |
| foco da janela (diálogos, sobreposições) | `ReaderActivity.onWindowFocusChanged` |
| tecla (volume) | `ReaderActivity.dispatchKeyEvent` |
| fim do leitor | `ReaderViewModel.onCleared` |
| total de páginas conhecido | `ReaderChapter.pages` após `ChapterLoader.loadChapter` |
| modo anônimo | `ReaderViewModel.incognitoMode` (por fonte) |

### 1.4 Testes

`domain` tem 5 classes de teste, `app` 19 (quase todas do Bubble Zoom), `data` **nenhuma** — não há
driver SQLite de JVM no projeto. Para testar queries e a migration sem aparelho, o plano propõe
adicionar `app.cash.sqldelight:sqlite-driver` **só como dependência de teste** do módulo `data`
(decisão em aberto, §10).

---

## 2. Regras deste trabalho

Espelham as do Bubble Zoom (CLAUDE.md), adaptadas:

1. **Um passo por sessão.** Nada de adiantar o passo seguinte.
2. **Código novo em pacotes próprios:**
   - domínio: `domain/…/tachiyomi/domain/readingstats/` (e `…/comics/` na Fase C);
   - dados: `data/…/tachiyomi/data/readingstats/`, `.sq` novos em `…/sqldelight/tachiyomi/data/`;
   - app: `app/…/eu/kanade/tachiyomi/readingstats/` (rastreador, integração) e
     `app/…/ui/readingstats/` + `app/…/presentation/readingstats/` (telas).
   Arquivo upstream recebe só chamadas curtas; se passar de ~15 linhas, a lógica vai para o pacote.
3. **Nunca inventar número.** Desconhecido é mostrado como desconhecido; nenhuma estimativa
   silenciosa; nenhuma porcentagem sem denominador conhecido.
4. **Um só módulo de definições de métricas** (`MetricDefinitions` no domínio), usado por cards,
   gráficos, metas, insights e exportação.
5. **Lógica pura na JVM, com testes** — máquina de estados do rastreador, fatiamento por dia,
   streak, metas, parser de numeração de issue, regras de insight.
6. **Relógio e fuso injetados** em tudo que depende de tempo (`kotlin.time.Clock` +
   `TimeZone` provider), para testar meia-noite e troca de fuso sem esperar nem mexer no aparelho.
7. **Migrations não destrutivas e versionadas**; cada uma com teste de migração a partir de um
   banco da versão anterior.
8. **Privacidade:** tudo local; nenhum dado novo em `telemetry`; modo anônimo não grava; uma
   preferência "Registrar estatísticas de leitura" permite desligar o registro.
9. **Bubble Zoom intacto.** Nenhum passo muda arquivo do pacote `bubblezoom`; o toque do Bubble
   Zoom conta como interação (passa por `dispatchTouchEvent` do leitor).
10. **Verificação no emulador ao fim de cada passo com efeito visível**, com screenshots
    `tools/shots/stats-<passo>-<n>.png` e log `ReadingStats …` no logcat (como o `BubbleZoom …`).
11. **Commits:** `stats: <descrição imperativa>`, um por passo; o usuário commita.

---

## 3. Modelo de dados

### 3.1 Migration 15 — Fase A

```sql
-- Uma sessão de leitura de um capítulo. Gravada incrementalmente: o rastreador faz UPDATE com
-- valores absolutos (nunca "+="), então repetir uma gravação não duplica nada.
CREATE TABLE reading_sessions(
    _id               INTEGER NOT NULL PRIMARY KEY,
    session_uuid      TEXT    NOT NULL UNIQUE,     -- chave estável: backup e restauração idempotentes
    manga_id          INTEGER REFERENCES mangas(_id)   ON DELETE SET NULL,
    chapter_id        INTEGER REFERENCES chapters(_id) ON DELETE SET NULL,
    -- chaves estáveis entre aparelhos e migrações de fonte; e retrato para quando a obra some
    source_id         INTEGER NOT NULL,
    manga_url         TEXT    NOT NULL,
    chapter_url       TEXT    NOT NULL,
    manga_title       TEXT    NOT NULL,
    chapter_name      TEXT    NOT NULL,
    chapter_number    REAL,
    started_at        INTEGER NOT NULL,            -- epoch ms UTC
    ended_at          INTEGER NOT NULL,            -- último instante gravado
    active_ms         INTEGER NOT NULL DEFAULT 0,
    first_page        INTEGER,
    last_page         INTEGER,
    pages_visited     INTEGER NOT NULL DEFAULT 0,  -- páginas distintas vistas nesta sessão
    pages_new         INTEGER NOT NULL DEFAULT 0,  -- além da marca d'água do capítulo (1ª vez)
    pages_reread      INTEGER NOT NULL DEFAULT 0,  -- distintas, já alcançadas antes
    page_advances     INTEGER NOT NULL DEFAULT 0,  -- trocas para uma página seguinte
    total_pages       INTEGER,                     -- NULL = desconhecido
    completed_chapter INTEGER NOT NULL DEFAULT 0,  -- a sessão alcançou a última página
    origin            INTEGER NOT NULL,            -- 0 leitor, 1 manual, 2 importado
    state             INTEGER NOT NULL,            -- 0 aberta, 1 fechada, 2 recuperada (processo morreu)
    zone_id           TEXT    NOT NULL             -- fuso no início da sessão (auditoria)
);
CREATE INDEX reading_sessions_started_idx ON reading_sessions(started_at);
CREATE INDEX reading_sessions_manga_idx   ON reading_sessions(manga_id, started_at);
CREATE INDEX reading_sessions_chapter_idx ON reading_sessions(chapter_id);
CREATE INDEX reading_sessions_open_idx    ON reading_sessions(state) WHERE state = 0;

-- A sessão fatiada por dia civil, no fuso EM VIGOR QUANDO O TEMPO ACONTECEU. Toda agregação
-- por dia/semana/mês lê daqui: GROUP BY local_date, sem conta de fuso em SQL.
CREATE TABLE reading_day_segments(
    session_id    INTEGER NOT NULL REFERENCES reading_sessions(_id) ON DELETE CASCADE,
    local_date    TEXT    NOT NULL,                -- 'YYYY-MM-DD'
    active_ms     INTEGER NOT NULL,
    pages_new     INTEGER NOT NULL,
    pages_visited INTEGER NOT NULL,
    PRIMARY KEY(session_id, local_date)
);
CREATE INDEX reading_day_segments_date_idx ON reading_day_segments(local_date);

-- Marca d'água por capítulo: o que torna "página nova" e "concluído" idempotentes.
CREATE TABLE chapter_reading_progress(
    chapter_id         INTEGER NOT NULL PRIMARY KEY REFERENCES chapters(_id) ON DELETE CASCADE,
    max_page_reached   INTEGER NOT NULL,           -- maior índice já exibido, qualquer sessão
    total_pages        INTEGER,                    -- último total conhecido (NULL = nunca carregado)
    first_completed_at INTEGER,                    -- 1ª vez que a última página foi alcançada
    completion_origin  INTEGER                     -- 0 leitor, 1 manual, 2 importado
);
```

Decisões embutidas:

- **`ON DELETE SET NULL` + retrato** nas sessões: quando uma fonte some, a obra é removida ou o
  capítulo desaparece numa atualização, o histórico de leitura **não some** e continua agregável;
  a UI mostra o título do retrato com um selo "obra removida".
- **Migração de fonte** (Mihon migra obra entre fontes): as sessões são reapontadas para o novo
  `manga_id`/`chapter_id` casando `chapter_number` reconhecido (a mesma regra que
  `MigrateMangaUseCase` usa para copiar lido e marcador; hoje ele **não** leva o histórico). Passo A5.
- **`reading_day_segments` escrito no momento**, não calculado depois: se o usuário lê das 23:50 às
  00:20, o rastreador fecha o segmento de ontem à meia-noite local e abre o de hoje. Se o fuso muda
  no meio (viagem), o segmento novo usa o fuso novo. Nenhuma consulta precisa saber de fuso.
- **Sessões aninhadas não existem:** uma sessão é sempre de **um** capítulo.

> **Como ficou na Fase A** (`reading_stats.sq` + `migrations/15.sqm`), diferenças em relação ao
> esboço acima:
> - a sessão guarda `pages_bits` (páginas vistas nela) e o capítulo guarda `pages_seen` (páginas já
>   alcançadas em qualquer sessão), como conjuntos de bits. "Nova" é página nunca vista, não "acima
>   da marca d'água": pular do 1 para o 20 e voltar ao 10 conta o 10 como novo, não como releitura;
> - a conclusão pelo leitor fica **na sessão** (`completed_at`, `completed_on`, `first_completion`),
>   e os "concluídos" do período contam por ela. Assim sobrevivem quando a fonte apaga e recria o
>   capítulo (o progresso do capítulo cai em cascata; a sessão, não). Marcados à mão e importados
>   ficam no progresso do capítulo;
> - `reading_stats_meta.recording_started_at` marca o início do registro; a migração importa o que o
>   Mihon sabia (capítulo lido → concluído "importado", datado pelo `history.last_read`, se houver;
>   capítulo parado numa página → páginas até ela contam como já vistas). Nada disso vira tempo.

### 3.2 Fases seguintes (migrations 16+)

- **B:** `reading_goals` (métrica, período, alvo, principal?, criada_em, arquivada_em) e
  `reading_plans` (alvo = obra ou coleção, ritmo, início, pausado). Mudar meta **não** toca sessões.
- **B/C:** `comic_collections(_id, uuid, kind [list|run|arc|event|reading_order], name,
  total_known NULL, notes)` e `comic_collection_items(collection_id, position REAL, manga_id NULL,
  chapter_id NULL, source_id, manga_url, chapter_url NULL, label)` — muitos-para-muitos com ordem
  explícita; um item é um capítulo (issue) **ou** uma obra inteira; o mesmo capítulo pode estar em
  várias coleções e o progresso de cada coleção conta cada capítulo **uma** vez.
- **C:** `comic_series_meta(manga_id PK, default_unit_type, publisher, imprint, universe,
  start_year, publication_status, provenance)`, `comic_unit_meta(chapter_id PK, unit_type,
  issue_label, issue_sort, volume_label, contains_issues NULL, release_year, page_count,
  provenance)`, `comic_credits(manga_id, chapter_id NULL, role, name, provenance)` e
  `comic_tags(manga_id, kind [character|genre|universe], value, provenance)`.
  `provenance`: 0 confirmado pelo usuário, 1 inferido (sugestão), 2 ComicInfo, 3 fonte.

---

## 4. Captura de leitura

### 4.1 Onde fica

- `ReadingSessionTracker` (**puro**, `domain/readingstats/tracker/`): recebe eventos, relógio e fuso;
  devolve comandos de persistência (`Open`, `Flush`, `Close`). Toda a regra mora aqui e é testada na
  JVM.
- `ReaderStatsRecorder` (`app/…/readingstats/`): liga o rastreador ao leitor, grava pelo repositório
  fora da thread principal, respeita modo anônimo e a preferência de registro.
- Ganchos upstream (uma linha cada): `ReaderViewModel.onPageSelected`, troca de capítulo,
  `onCleared`; `ReaderActivity.onResume/onPause/onWindowFocusChanged/dispatchTouchEvent/
  dispatchKeyEvent`.

### 4.2 Quando o tempo conta

O relógio ativo corre **só** quando todas valem:

| Condição | Fonte |
|---|---|
| leitor em primeiro plano | `onResume`/`onPause` (tela desligada também chega como `onPause`) |
| janela com foco | `onWindowFocusChanged` (diálogo de configurações, barra de notificação) |
| página atual carregada | estado da `ReaderPage` atual = pronta; carregando/erro não conta |
| interação recente | último toque, tecla ou troca de página há menos de **T ocioso** (padrão 2 min, configurável) |

Ao passar T ocioso sem interação, o tempo desde a última interação **além de um limite curto**
não conta (o tempo até a interação + uma margem de leitura da página conta; decidir a margem no
passo A3 com medição — proposta: conta até 60 s após a última interação).

### 4.3 Abrir, continuar, fechar

- **Abre** na primeira página **carregada** exibida de um capítulo, fora do modo anônimo.
- **Continua a mesma sessão** se o leitor volta ao mesmo capítulo em até **R** (padrão 10 min)
  após pausar; senão fecha a anterior e abre outra. Regra explícita, testada.
- **Troca de capítulo** no leitor fecha a sessão do anterior e abre a do novo.
- **Grava incrementalmente:** a cada troca de página e a cada 15 s de tempo ativo, `UPDATE` com
  valores absolutos e `ended_at = agora`. Processo morto perde no máximo 15 s.
- **Recuperação:** na abertura do app, sessões com `state = 0` (aberta) e `ended_at` mais velho que
  R são fechadas como `state = 2` (recuperada) **no último instante gravado** — nenhum tempo é
  inventado entre a última gravação e a morte do processo.

### 4.4 Páginas

Por sessão: o conjunto de índices exibidos (em memória) e a marca d'água do capítulo lida no início.

- **visitadas** = índices distintos exibidos na sessão;
- **novas** = distintas acima da marca d'água anterior (e a marca sobe);
- **relidas** = distintas ≤ marca d'água, ou qualquer página de capítulo já concluído;
- **avanços** = trocas para índice maior que o anterior (voltar e avançar de novo conta avanço, mas
  não página nova);
- `InsertPage` (metade de página dupla dividida) não conta, como hoje no Mihon;
- trocar rápido de página conta visita, mas o tempo segue a regra de ociosidade (não há "tempo por
  página" mínimo inventado).

**Meta de páginas** usa **páginas novas**; releitura aparece separada.

### 4.5 Conclusão de capítulo

`completed_chapter = 1` quando a sessão alcança o último índice. `chapter_reading_progress.
first_completed_at` só é preenchido **uma vez** — reabrir, reler ou remarcar não soma de novo.

**Marcação manual** (`SetReadStatus`): grava `first_completed_at`/`completion_origin = manual` se ainda
vazio, **sem sessão, sem tempo, sem páginas**. Em "concluídos no período" aparece separado
("+12 marcados como lidos"); por padrão **não conta para metas** (decisão em aberto, §10).

### 4.6 Legado

- No primeiro uso da versão com registro, grava-se `stats_recording_started_at`. Qualquer período
  anterior mostra tempo e páginas como **indisponível** (não zero).
- `history.time_read` total continua visível na tela de biblioteca, rotulado "tempo aproximado
  registrado pelo Mihon (inclui pausas)".
- Conclusões legadas (`chapters.read = 1` com `history.last_read`) podem entrar como
  `completion_origin = importado`, datadas por `last_read`, **somente** para contagem de concluídos
  e com o selo "importado"; nunca geram tempo nem páginas.

---

## 5. Métricas — um só lugar

`domain/readingstats/metrics/MetricDefinitions.kt` (puro), consumido por tudo:

| Métrica | Definição | Fonte |
|---|---|---|
| Tempo ativo | soma de `active_ms` dos segmentos do período | `reading_day_segments` |
| Páginas novas | soma de `pages_new` | segmentos |
| Páginas relidas / visitadas | soma de `pages_reread` / `pages_visited` | sessões/segmentos |
| Capítulos concluídos | capítulos com `first_completed_at` no período, por origem | `chapter_reading_progress` |
| Issues / volumes concluídos | idem, filtrado pelo **tipo de unidade efetivo** (Fase C); antes disso a UI diz "capítulos" | + `comic_unit_meta` |
| Dia de leitura | `local_date` com tempo ativo ≥ **limiar** (padrão 60 s, documentado na tela de definições) | segmentos |
| Streak atual | dias de leitura consecutivos terminando **hoje ou ontem** (hoje ainda não "quebrou") | segmentos |
| Maior streak | maior sequência histórica | segmentos |
| Semana | segunda a domingo por padrão, ou início da semana do idioma — **uma** definição (§10) | preferência |
| Comparação | variação % só se a base anterior > 0 **e** o período anterior está todo depois de `stats_recording_started_at`; senão texto ("primeira semana com dados") | — |
| Progresso de série/run | `concluídos / total` só com total conhecido; senão "X concluídos" | coleções + metadados |

Todas as consultas filtram por intervalo de `local_date` ou `started_at` com índice; nenhum card
carrega todas as sessões.

---

## 6. Backup e restauração

| Onde | Campo novo | Conteúdo |
|---|---|---|
| `BackupManga` | `@ProtoNumber(113) readingSessions` | sessões da obra, com `session_uuid`, `chapter_url`, retrato, segmentos por dia |
| `BackupManga` | `@ProtoNumber(114) chapterProgress` | marca d'água e conclusões por `chapter_url` |
| `BackupManga` | `@ProtoNumber(115) comicMeta` (Fase C) | metadados da série e das unidades |
| `Backup` | `@ProtoNumber(107) comicCollections` (Fases B/C) | coleções com itens por `(source, manga_url, chapter_url)` |
| `Backup` | `@ProtoNumber(108) readingGoals` (Fase B) | metas e planos |
| `BackupOptions` / `RestoreOptions` | `readingStats` | opção nova na tela de backup, ligada por padrão |

- Números livres conferidos: `BackupManga` vai até 112 (`memo`), `Backup` até 106.
- **Restaurar é idempotente:** sessão casa por `session_uuid`; progresso por `chapter_url` fica com o
  **máximo** (marca d'água) e o **mínimo** não nulo (`first_completed_at`).
- Sessão cujo capítulo não existe no destino é restaurada com `chapter_id = NULL` e o retrato.
- Backup antigo (sem os campos) desserializa com listas vazias: restaura exatamente como hoje.
- Testes: ida e volta protobuf; decodificar bytes de backup antigo; restaurar duas vezes.

> **Como ficou na Fase A:** em vez de campos em `BackupManga`, um único
> `Backup.@ProtoNumber(9001) backupReadingStats` guarda tudo (início do registro, sessões com seus
> dias, progresso por capítulo), com obra e capítulo identificados por fonte + URL. Assim sessões de
> obras que não entram no backup (fora da biblioteca, ou removidas) também vão, e o número alto não
> colide com campos futuros do Mihon. Fases B/C acrescentam campos dentro de `BackupReadingStats`.

---

## 7. Telas

### 7.1 Navegação

A aba **Histórico** passa a hospedar um `TabbedScreen` com **Stats** (padrão) e **Histórico** (a tela
atual, intacta). Mantém: reselecionar a aba retoma o último capítulo; o atalho `Tab.History` do
`HomeScreen` abre a aba. Um toque no heatmap troca para a sub-aba Histórico com o filtro do dia.
A tela Mais → Estatísticas continua existindo (dados da biblioteca), com link a partir de Stats.

### 7.2 Stats (ordem dos blocos)

1. **Esta semana** — páginas novas, capítulos/issues, tempo ativo, dias de leitura; tendência só se
   comparável.
2. **Continuar lendo** — obras recentes com o próximo capítulo e a página de retomada (reusa
   `GetNextChapters`); um toque abre o leitor.
3. **Seus objetivos** (B) — meta principal + metas opcionais, próximo passo.
4. **Atividade** (B) — heatmap; toque abre o Histórico do dia.
5. **Perto de terminar** (C) — só obras/runs com total conhecido.
6. **Insights** (D) — frases curtas com regra rastreável ("por quê?" mostra os números).
7. Links: **Detalhes por período** (D) e **Histórico**.

Estados: sem dados ainda ("comece a ler e os números aparecem aqui"), dados parciais (período antes
do registro: "indisponível antes de <data>"), metadados faltando (explicação + atalho para
classificar). Sem mensagens de culpa: meta perdida mostra o que falta, não o que "falhou".

Acessibilidade: cada card com `contentDescription` textual completo; gráficos com resumo em texto;
áreas de toque ≥ 48 dp; escala de fonte até 200 % sem cortar números; temas claro/escuro.

### 7.3 Histórico

- Lista atual preservada (uma linha por obra, ações atuais).
- **Filtro de data** (chip "Todas as datas / <dia>") e, quando ativo, a lista do dia vem de
  **sessões** (com tempo e páginas) mais as entradas legadas daquele dia com selo "importado".
- Filtros por obra e por tipo (leitura / marcado como lido) se couberem sem refazer a tela.
- Excluir sessão (e, no passo A8, editar início/fim) recalcula os agregados do dia.

---

## 8. Passos

Cada passo: **arquivos**, **testes**, **verificação**, **aceite**. Os arquivos upstream tocados estão
marcados com ⬆ e precisam de aprovação antes de editar (regra do CLAUDE.md).

### Fase A — base confiável e primeira tela útil

**A1 — Esquema e repositório.**
Arquivos: `migrations/15.sqm`; `data/reading_sessions.sq`, `reading_day_segments.sq`,
`chapter_reading_progress.sq`; `domain/readingstats/model/*`, `repository/ReadingStatsRepository.kt`;
`data/readingstats/ReadingStatsRepositoryImpl.kt`; `data/build.gradle.kts` ⬆ (driver SQLite só em
teste).
Testes: repositório na JVM (inserir/atualizar idempotente, `SET NULL` ao apagar capítulo/obra,
cascata dos segmentos); **migração 14→15 sobre um banco com histórico antigo** (dados intactos).
Aceite: app abre sobre banco antigo; tabelas vazias; nada visível muda.

**A2 — Métricas e tempo civil (puro).**
Arquivos: `domain/readingstats/metrics/*` (períodos, semana, dia de leitura, streak, comparação,
fatiamento de intervalo por meia-noite local).
Testes: meia-noite, horário de verão, troca de fuso, início de semana, base zero, período antes do
registro.

**A3 — Rastreador (puro).**
Arquivos: `domain/readingstats/tracker/ReadingSessionTracker.kt`.
Testes: pausa em segundo plano; foco perdido; ociosidade; página carregando; voltar páginas; troca
rápida; releitura de capítulo concluído; retomada dentro/fora de R; troca de capítulo; gravação a
cada 15 s; processo morto → recuperação sem inventar tempo; sessão atravessando meia-noite; troca
de fuso no meio; modo anônimo = nada.

**A4 — Ganchos no leitor.**
Arquivos: `app/…/readingstats/ReaderStatsRecorder.kt`, `ReadingStatsPreferences.kt`;
`ReaderViewModel.kt` ⬆ e `ReaderActivity.kt` ⬆ (chamadas de uma linha); recuperação de sessões
abertas no início do app (`App.kt` ⬆ ou inicializador existente).
Verificação no emulador: ler parte de um capítulo do capítulo local de teste, ir ao fundo, voltar,
voltar páginas, avançar; logcat `ReadingStats session …` mostra tempo ativo coerente com o
cronômetro, páginas novas vs relidas corretas; matar o processo (`adb shell am kill`) e reabrir →
sessão recuperada, sem tempo extra. Bubble Zoom ligado e desligado se comportam como antes.
Aceite A (parcial): ler parte de um issue gera tempo e progresso; sair e voltar não duplica
páginas.

**A5 — Marcação manual, legado e migração de fonte.**
Arquivos: `SetReadStatus.kt` ⬆ (registrar conclusão manual), `mihon/domain/migration/usecases/
MigrateMangaUseCase.kt` ⬆ (reapontar sessões), `domain/readingstats/interactor/*`; marcador `stats_recording_started_at`.
Testes: marcar lido não cria sessão nem tempo; remarcar não duplica; migração leva sessões junto.
Aceite A: marcar como lido não fabrica duração; histórico antigo não aparece como "0 minutos".

**A6 — Backup e restauração.**
Arquivos: `BackupManga.kt` ⬆, `Backup.kt` ⬆, `MangaBackupCreator.kt` ⬆, `MangaRestorer.kt` ⬆,
`BackupOptions.kt` ⬆/`RestoreOptions.kt` ⬆, modelos novos `BackupReadingSession.kt`,
`BackupChapterProgress.kt`.
Testes: ida e volta; backup antigo; restaurar duas vezes; sessão órfã.
Verificação: criar backup no emulador, limpar dados do app **só se o usuário autorizar** (regra de
não mexer em dados do app), restaurar, conferir totais.

**A7 — Aba Stats + Histórico.**
Arquivos: `HistoryTab.kt` ⬆ (vira `TabbedScreen`), `ui/readingstats/StatsHomeScreen.kt`,
`StatsHomeViewModel.kt`, `presentation/readingstats/*` (cards "Esta semana", "Continuar lendo",
estados vazios/indisponíveis), filtro de dia no Histórico (`HistoryScreen.kt` ⬆,
`HistoryViewModel.kt` ⬆, query nova), strings (`base` + `pt-rBR`).
Verificação: screenshots claro/escuro, tela compacta (`adb shell wm size 720x1280` e restaurar),
fonte grande; estados vazio / só legado / com sessões.
Aceite A: leitura após meia-noite aparece nos dias certos; o Histórico antigo continua igual.

**A8 — Correção de sessões.**
Excluir sessão (e editar início/fim se couber) com recálculo dos segmentos; testes de recálculo.

### Fase B — hábito e metas

**B1 — Metas.** Diárias/semanais por páginas novas, capítulos/issues ou minutos; uma **principal**,
outras opcionais; migration 16. Mudar meta não reescreve sessões (teste).
**B2 — Streak e recordes.** Atual/recorde, frequência semanal, recordes pessoais (maior dia, maior
sessão, semana mais longa). Streak sem punição antes do fim do dia (teste).
**B3 — Heatmap.** Calendário de intensidade (acessível: cada dia com descrição); toque → Histórico do
dia (teste de navegação e de fuso: o dia do toque é o `local_date` gravado).
**B4 — Planos de leitura.** Alvo = obra ou lista (a tabela de coleções nasce aqui, com `kind = list`);
ritmo desejado; progresso; restante **só se conhecido**; previsão só com pelo menos 2 semanas de
dados (teste: previsão desconhecida aparece como tal).
**B5 — Recomendações acionáveis.** "Continue de onde parou", "faltam 3 capítulos", "7 nesta semana para
voltar ao ritmo do plano" — cada uma com regra e teste; sem culpa.
Aceite B do documento coberto por B1–B4 (plano com itens de duas obras soma cada item uma vez).

> **Como ficou na Fase B** (`reading_plans.sq`, `migrations/16.sqm`):
> - `reading_goals` tem chave (métrica, período): no máximo 6 metas (páginas novas, capítulos
>   concluídos no leitor, minutos × dia, semana), uma principal. Tela própria de metas.
> - `reading_collections` + `reading_collection_items` + `reading_plans`: todo plano lê uma coleção
>   (`ENTRY` = uma obra inteira; `LIST` = obras e capítulos escolhidos, de várias séries). Um item é
>   uma obra inteira (inclusive capítulos que a fonte acrescentar) ou um capítulo; o total é a união,
>   sem repetição, na ordem dos itens. Em C, runs/arcos/eventos serão novos `kind` da mesma coleção.
> - Concluído num plano = capítulo lido (`chapters.read`); o ritmo conta só o que foi concluído desde
>   o início do plano, com data (primeira conclusão no leitor ou marcação à mão). A meta de uma semana
>   parcial é proporcional aos dias.
> - Previsão só com 14 dias de registro e 2 capítulos do plano concluídos nos últimos 28 dias; senão,
>   "ainda falta leitura para estimar". "No ritmo do plano, termina em N semanas" é conta sobre o
>   ritmo escolhido, não previsão.
> - Próximos passos: meta perto (≤ 25 % restante), plano perto do fim (≤ 3), manter o ritmo, voltar
>   ao ritmo (até 2 semanas de atraso), sugerir outro ritmo (mais que isso), à frente.

### Fase C — organização de HQs

**C1 — Metadados.** Migration 17; editor na tela da obra (`MangaScreen` ⬆, um item de menu que abre
tela própria do pacote); tipo de unidade (issue/TPB/hardcover/omnibus/outro/desconhecido) por obra
com exceção por capítulo; editora, selo, universo, ano, criadores, personagens, gêneros;
**sugestões** a partir de ComicInfo (fonte local) e do número do capítulo, marcadas "inferido" até o
usuário confirmar. Parser de numeração (`#0`, `#1/2`, `Annual 1`, `1.MU`, variantes) com testes.
Aceite C: TPB não conta como um issue; numeração especial não quebra ordenação.
**C2 — Runs, arcos, eventos, ordens de leitura.** Coleções que atravessam obras e fontes, com ordem
explícita; progresso com total conhecido; uma HQ num evento e numa run não duplica leitura (teste).
**C3 — Estado da biblioteca e backlog.** Não iniciado / em andamento / concluído / total desconhecido
("desconhecido" nunca vira 0 %); backlog = unidades disponíveis não lidas; adicionados vs concluídos
por período, com a unidade sempre visível (obras vs issues).
**C4 — Perto de terminar e séries paradas.** Só com total conhecido; "parada há N dias" com opção de
esconder lembretes.
**C5 — Backup dos metadados e coleções.**

#### Coleta de metadados (decidido em 24/09/2026)

**O que as fontes entregam hoje** (biblioteca do emulador): editora só como gênero (`DC Comics`),
autor quase sempre vazio, artista nunca, ano só no título (`Batman (2025-)`), e números que o Mihon
ordena errado: `#Annual 2025` vira capítulo 2025; `TPB_(Part_1)`, `Issue #1` e `#1 Noir Edition` viram
todos 1.0. O ComicInfo que o Mihon grava nos downloads repete a fonte e não acrescenta nada.

**Camadas, com a origem guardada em cada campo** (a primeira que houver é a sugerida):

1. **Confirmado** pelo usuário — o único que conta como certo nas estatísticas. Editor por série,
   "aplicar a todos os capítulos", exceção por capítulo, aceitar sugestões em lote.
2. **Metron** (metron.cloud), sob demanda — ver abaixo; **GCD** (comics.org) como fallback do
   Metron (decidido em 24/09/2026, com o cadastro no Metron fechado) — ver abaixo.
3. **ComicInfo.xml** de arquivos da fonte local. Os campos que o Mihon ainda não lê (`Publisher`,
   `Imprint`, `Volume`, `Count`, `Format`, `StoryArc`, `Characters`, `Teams`) são lidos por uma
   classe própria do pacote (`ComicInfoMetadata`), sem mexer no `ComicInfo.kt` do `core-metadata`.
4. **Fonte**: autor, artista, status, descrição.
5. **Inferido**, sempre como sugestão: título `Nome (AAAA-)`/`(AAAA)` → série e ano de início;
   gênero que bate com uma lista de editoras → editora; nome do capítulo → `Annual`, `TPB`, parte,
   variante, `#0`, `#1/2`, `.MU` (rótulo + posição de ordenação própria); páginas conhecidas pela
   leitura só como reforço de issue × encadernado. Nunca a partir só do nome de arquivo.

**Metron** (escolhido em vez do Comic Vine: projeto comunitário feito para apps de terceiros, sem a
restrição de uso não comercial nem a exigência de link em cada tela; campos já estruturados):
- desligado por padrão; o usuário gera um token na conta dele em metron.cloud e cola na tela
  "Metadados online" (menu da aba Stats; preferência privada, fora do backup comum — como as
  credenciais dos rastreadores). Autenticação `Authorization: Bearer <token>` (knox);
- só por ação explícita, uma série por vez: "Buscar online" no editor da série envia **apenas o
  título e o ano** da série; nada da biblioteca nem da leitura; nada em segundo plano;
- o usuário escolhe a série certa na lista; o app lê os issues dela e casa com os capítulos pelo
  número (`number` do Metron contra o rótulo confirmado ou o nome do capítulo; a lista de issues
  não traz `alt_number`); só capítulos regulares casam — anual, especial e encadernado são séries
  à parte no Metron; o que não casar fica sem sugestão;
- detalhe do issue só para os capítulos casados; tudo chega com origem "Metron" e link para
  `resource_url`; aceitar em lote ou por campo; editar vira "confirmado";
- limites: fila de ≤ 20 pedidos/minuto; 429 espera o `Retry-After` (até 5 min; mais que isso é a
  cota do dia: para e guarda o que veio); detalhe de cada issue em cache no aparelho e pedido de novo
  só quando o `modified` da lista de issues muda (no lugar de `modified_gt`, que exigiria guardar a
  data da última busca); a série e cada capítulo casado guardam o id do Metron;
- mapeamento (campos do modelo `Issue` do cliente oficial Mokkari): `publisher`, `imprint` → editora,
  selo; `series.name/volume/year_began/series_type` → série e tipo de unidade padrão (tipos de
  encadernado → TPB/capa dura/omnibus, demais → issue); `number` → rótulo e ordenação;
  `cover_date`/`store_date` → ano; `page_count`; `credits[].creator/role` → roteirista, desenhista,
  colorista etc.; `characters`, `teams`, `universes`; `arcs` → sugestão de arco em C2; `reprints` →
  quantos e quais issues um encadernado reúne; `cv_id`/`gcd_id` guardados como referência;
- testes sem rede: mapeamento e casamento de números sobre respostas JSON escritas a partir do
  modelo do Mokkari (sem token não há como gravar respostas reais); fila de limite;
- verificação no aparelho exige o token do usuário: **quem cola o token é o usuário** (não digito
  credenciais).

**GCD — Grand Comics Database** (fallback do Metron; API em comics.org/api, schema OpenAPI público):
- usado quando não há token do Metron, quando o Metron recusa, estoura a cota, falha ou não acha a
  série; a janela de escolha diz por que foi ao GCD e dá o crédito (dados CC BY-SA 4.0);
- responde sem login, mas **30 pedidos por hora** (`settings.py` do gcd-django: `anon 30/hour`,
  `user 2000/day`); login opcional do próprio usuário (Basic, preferência privada) sobe para 2.000
  por dia — quem digita é o usuário;
- a busca (`series/name/<nome>/year/<ano>/`) já traz os issues da série (`active_issues` +
  `issue_descriptors`, com impressões e variantes entre colchetes), então escolher a série não
  gasta pedido; a editora vem como link (um pedido, guardado em cache);
- detalhe só dos issues casados, em cache por 30 dias (o GCD não informa data de mudança); quando a
  cota acaba no meio, o que veio fica e a próxima busca continua pelo cache;
- mapeamento: `name`, `year_began/ended`, `publishing_format`/`binding` → formato (edição encadernada
  capa dura/TPB, graphic novel → outro, resto → issue); total = números distintos quando a série
  terminou; `key_date` (`2011-07-00` → `2011-07`), `page_count`; créditos em texto da história
  principal (`script`, `pencils`, `inks`, `colors`, `letters`) e da capa; `editing`; personagens em
  texto, com grupos `Equipe [membro; membro]` virando equipe + personagens; não há arcos;
- anonimato "provavelmente será desligado" segundo a própria wiki: sem login, a busca passa a
  falhar com "acesso recusado".

**Fora por agora:** importação de listas `.cbl`; Komga/Kavita como fonte de metadados.

### Fase D — análises e retrospectivas

**D1 — Detalhes por período.** 7 dias / 30 dias / ano / tudo / intervalo; gráficos com rótulos e
resumo textual; tela de definições (páginas, tempo ativo, issue, streak, backlog).
**D2 — Ritmo e hábitos.** Ritmo médio, evolução semanal/mensal, sessões médias/longas, dias e
horários preferidos, estimativa de término **condicional** ("no seu ritmo das últimas 4 semanas").
**D3 — Rankings por metadado.** Editora, selo, roteirista, artista, gênero, personagem, universo,
década — só com cobertura mínima (proposta: ≥ 60 % do tempo do período classificado), sempre com
"baseado em X % das suas leituras"; "desconhecido" nunca lidera ranking.
**D4 — Insights e retrospectivas.** Regras rastreáveis (cada insight abre os números que o geraram);
resumo mensal e retrospectiva anual opcionais; compartilhar só por ação do usuário (imagem/texto
gerado no aparelho, via intent de compartilhamento).

---

## 9. Matriz de testes (pedido do documento → onde)

| Cenário | Teste |
|---|---|
| pausa em segundo plano, foco perdido, ociosidade | A3 (JVM) + A4 (emulador) |
| fechamento inesperado | A3 recuperação + A4 `am kill` |
| reabertura dentro/fora da janela R | A3 |
| navegação para trás, troca rápida, releitura | A3 |
| marcação manual | A5 |
| capítulo sem páginas conhecidas | A3 (`total_pages = NULL`), A7 (UI "desconhecido") |
| meia-noite, fuso, mudança de fuso | A2 + A3 |
| duplicatas (reabrir, remarcar, restaurar 2×) | A3, A5, A6 |
| séries incompletas, TPB vs issue | C1, C3 |
| run entre séries, item em duas coleções | C2 |
| backup/restauração, backup antigo | A6, C5 |
| banco com histórico antigo | A1 (migração 14→15) |
| UI claro/escuro, tela compacta, fonte grande | A7, B3, D1 (screenshots) |

Comandos: `./gradlew :domain:test :data:test :app:testDebugUnitTest --tests "*readingstats*"`,
`./gradlew :app:compileStandardDebugKotlin`, `./gradlew :app:spotlessCheck`, e o build do APK só
antes de instalar no emulador.

---

## 10. Decisões em aberto (propostas padrão entre parênteses)

> Na Fase A foram adotados todos os padrões propostos abaixo (o usuário aprovou o plano sem mudá-los).

1. **Aba:** Stats como sub-aba padrão dentro de Histórico, rótulo da aba inferior continua
   "Histórico"? (sim / sim)
2. **Ociosidade:** T = 2 min e margem de 60 s após a última interação? (sim, configurável)
3. **Dia de leitura:** limiar de 60 s de tempo ativo? (sim)
4. **Semana:** segunda a domingo, ou seguir o idioma do aparelho? (seguir o idioma, com opção)
5. **Marcados como lidos manualmente** contam para metas? (não, aparecem separados)
6. **Registro ligado por padrão**, com preferência para desligar? (sim)
7. **Dependência de teste** `sqldelight sqlite-driver` no módulo `data`? (sim, só teste)
8. **Conclusões legadas** entram como "importadas" nos concluídos? (sim, com selo; nunca tempo/páginas)
9. **Mais → Estatísticas** fica como está, com link a partir de Stats? (sim)

---

## 11. Arquivos upstream que o plano toca

| Arquivo | Passo | Por que não dá para ficar no pacote |
|---|---|---|
| `ReaderViewModel.kt` | A4 | é quem recebe troca de página/capítulo e o fim do leitor |
| `ReaderActivity.kt` | A4 | ciclo de vida, foco e entrada (toque/tecla) só existem aqui |
| `eu/kanade/tachiyomi/App.kt` | A4 | recuperação de sessões abertas precisa rodar no início |
| `SetReadStatus.kt` | A5 | único caminho da marcação manual |
| `mihon/domain/migration/usecases/MigrateMangaUseCase.kt` | A5 | é onde capítulos trocam de id |
| `data/build.gradle.kts` | A1 | dependência de teste |
| `Backup*.kt`, criadores/restauradores, opções | A6, C5 | formato de backup é upstream |
| `HistoryTab.kt`, `HistoryScreen.kt`, `HistoryViewModel.kt` | A7 | a aba e a lista existentes |
| `ui/manga/MangaScreen.kt`, `presentation/manga/MangaScreen.kt`, `MangaToolbar.kt` (menu) | C1 | ponto de entrada do editor de metadados: um item no menu da obra, repassado pelas duas diagramações |
| `strings.xml` (base, pt-rBR) | todos | recursos de texto do projeto |

---

## Status

- [x] A1 esquema e repositório — `reading_stats.sq`, `migrations/15.sqm`, `ReadingStatsRepositoryImpl`
- [x] A2 métricas e tempo civil — `ReadingCalendar`, `ReadingMetrics`
- [x] A3 rastreador — `ReadingSessionTracker`
- [x] A4 ganchos no leitor — `ReaderStatsRecorder`
- [x] A5 marcação manual, legado, migração de fonte — `RecordManualReadStatus`, `MoveReadingStats`
- [x] A6 backup e restauração — `BackupReadingStats` (campo 9001)
- [x] A7 aba Stats + Histórico — `ReadingHomeScreen`, `ReadingStatsContent`, `ReadingDayContent`
- [x] A8 correção de sessões — apagar sessão (e tudo) com recálculo; **editar início/fim não foi feito**
- [x] B1 metas — `ReadingGoalsScreen`, `GoalRules`; até 6 metas, uma principal
- [x] B2 sequência, frequência e recordes — `ReadingHabits`
- [x] B3 calendário de intensidade — cartão "Atividade"; tocar num dia abre o Histórico do dia
- [x] B4 planos de leitura — `ReadingPlansScreen`, `ReadingPlanEditorScreen`, `PlanRules`
- [x] B5 próximos passos — `NextSteps`
- [x] C1 metadados — `comic_metadata` (migração 17), `ComicMetadataScreen` (menu da obra), sugestões
  por camada (`ComicSuggestions`), `IssueNumbers`, `ComicInference`, ComicInfo (`ComicInfoReader`),
  Metron e GCD (`OnlineMetadata`, `MetronApi`, `GcdApi`)
- [x] C2 coleções — `ReadingCollectionsScreen`, `ReadingCollectionEditorScreen` (ordem explícita,
  arco a partir de metadados), plano que lê uma coleção, `CollectionRules`
- [x] C3 biblioteca e backlog — `ComicLibrary`, cartão "Biblioteca", `ComicLibraryScreen`
- [x] C4 perto de terminar e paradas — cartões com lembrete escondível
- [x] C5 backup — `BackupReadingStats` campos 6 (metadados confirmados) e 7 (coleções)
- [ ] D1–D4

### Verificação da Fase A (24/09/2026)

**Testes na JVM (46 novos):** `ReadingSessionTrackerTest` (18: primeiro plano/foco/página
carregada, silêncio curto e longo, voltar páginas, folhear rápido, conclusão única, releitura de
capítulo já lido, legado parado numa página, marcado à mão, retomar dentro/fora da janela, sessão
salva retomada, troca de capítulo, gravação a cada 15 s, meia-noite, troca de fuso),
`ReadingMetricsTest` (7: meia-noite, dia de 23 h, início de semana, cobertura, soma, mesmo trecho da
semana passada, tendência só com base cheia), `ChapterPagesTest` (6), `ReadingStatsRepositoryImplTest`
(12, SQLite real via `sqlite-driver`: gravar duas vezes, sessão que sobrevive ao capítulo, apagar
repassa a primeira conclusão, marcar/desmarcar à mão, recuperação, retomada, migração de obra,
restaurar duas vezes, **migração 15 sobre um banco antigo**) e `BackupReadingStatsTest` (3: ida e
volta, backup antigo sem o campo, backup novo lido por um Mihon sem o campo). Mutações de conferência:
somar em vez de gravar o dia e ignorar o limite de silêncio — os testes pegaram as duas.

**Emulador** (`tools/shots/stats-*.png`), sobre a biblioteca real (4 obras, 51 capítulos, 8 linhas de
histórico):
- migração 15→16 no aparelho: 3 capítulos lidos viraram concluídos "importados" com a data do
  `last_read`, 4 parados numa página ficaram com essa página; histórico intacto (8 linhas, mesmo
  `time_read` total);
- sessão cronometrada (Absolute Batman #15): 60,1 s da página 1 ao HOME + 21,4 s depois de voltar =
  81,5 s ativos — os 20 s em segundo plano e o carregamento ficaram de fora; 3 páginas novas;
  voltar uma página não contou de novo;
- reabrir o capítulo em 2 min continuou a mesma sessão; `force-stop` no meio perdeu ≤ 5 s; depois de
  10 min a sessão aberta virou "Interrompida" e a leitura seguinte abriu outra;
- ler até a última página: 1 concluído (primeira vez), 18 novas + 1 relida; passar ao capítulo
  seguinte fechou uma sessão e abriu a outra; o #16, parado na página 26 antes do registro, contou
  a página 1 como relida;
- marcar 2 capítulos como lidos à mão: "Marcados como lidos à mão: 2", sem tempo nem páginas;
  desmarcar: sumiu;
- apagar a sessão que concluiu: concluídos 1 → 0, páginas 21 → 3, tempo 3 → 2 min;
- registro pausado: leitor aberto e usado, 0 gravações; retomado;
- telas: claro, escuro, 360 dp, fonte 200 % (números passam a 2×2), pt-BR (semana de domingo,
  "Registro desde 24 de set. de 2026"), dia anterior ao registro ("tempo e páginas não foram
  medidos"), seletor de dia sem datas futuras.

**Não verificado no aparelho:** criar e restaurar um backup (exige escolher arquivo e restaurar sobre
os dados do emulador; coberto pelos testes de repositório e de protobuf); migrar uma obra entre
fontes (coberto pelo teste de repositório); TalkBack. Modo anônimo usa o mesmo caminho do registro
pausado.

### Verificação da Fase B (24/09/2026)

**Testes na JVM (34 novos, 421 no total, todos passando):** `GoalRulesTest` (6: meta diária e dias
cumpridos, dias antes do registro nem cumpridos nem perdidos, semanal, minutos inteiros, marcados à mão
não contam, ordem), `ReadingHabitsTest` (9: hoje ainda sem leitura não quebra a sequência, dia sem
leitura quebra, recorde, dia abaixo de 1 min não conta, virada de mês, frequência só com semanas
inteiras, recordes, níveis do calendário), `PlanRulesTest` (10: run em duas séries conta cada
capítulo uma vez, ritmo desde o início, capítulo lido sem data, previsão desconhecida sem 14 dias ou
sem 2 concluídos, previsão pelo ritmo recente, plano concluído, passos de manter/voltar/ajustar
ritmo, perto do fim, pausado sem passos, meta perto primeiro), `ReadingPlanRepositoryImplTest` (8,
SQLite real: meta principal, **mudar meta não reescreve sessões**, plano em duas séries + scanlator
oculto, obra inteira pega capítulos novos, data de conclusão do leitor ou da marcação, editar e apagar
sem tocar em leitura, restaurar duas vezes em outro aparelho, **migração 16**), backup de metas e planos
(ida e volta).

**Emulador** (pt-BR, tema escuro — como o app estava configurado):
- migração 16→17 no banco do aparelho, sem erro;
- metas: páginas/dia 4 e minutos/semana 30; a primeira virou principal; trocar a principal desmarca a
  outra; o cartão mostrou "3 de 4 páginas novas", "2 de 30 minutos lendo" e o passo "1 página nova
  para a meta de hoje"; depois de ler uma página, "4 de 4" e o passo sumiu;
- plano Absolute Batman (inteiro, 26) + Batman (2025-) #1–#3: "3 de 29 capítulos", "no ritmo do
  plano, termina em 6 semanas", previsão "ainda falta leitura deste plano para estimar"; o passo "Com
  mais 3 capítulos esta semana… fica no ritmo" abriu o Absolute Batman #1 (primeiro não lido do
  plano); pausar mostrou "Pausado" e tirou os passos do plano; editar recarregou os itens;
- calendário de setembro: dia 24 marcado, dias antes do registro em branco, setas travadas no mês do
  início; **tocar no 24 abriu o Histórico de quinta-feira, 24 de setembro**;
- sequência "1 dia · recorde 1 dia"; recordes com datas curtas;
- backup criado pela tela de backup (opção "Estatísticas de leitura" marcada): o arquivo tem o campo
  9001 com 3 sessões, 11 progressos, 2 metas e o plano com 4 itens (lido com um decodificador
  protobuf à parte);
- fonte 200 % e tela de 360 dp sem cortes.

**Não verificado:** restaurar esse backup no aparelho; tema claro dos cartões novos (o app está no
escuro por escolha do usuário); TalkBack.

### Verificação da Fase C (24/09/2026)

**Decisões tomadas durante a fase:**
- **GCD como fallback do Metron** (pedido do usuário com o cadastro do Metron fechado): sem token,
  ou quando o Metron recusa, estoura a cota, falha ou não acha a série, a busca vai ao GCD, e a
  janela diz por quê. Limite anônimo do GCD: 30 pedidos/hora (`settings.py` do gcd-django); login
  opcional do usuário sobe para 2.000/dia.
- ComicInfo lido por uma classe própria (`ComicInfoMetadata`); o `ComicInfo.kt` do `core-metadata`
  não mudou. Upstream tocado só no menu da obra (3 arquivos, 18 linhas).
- Metadados confirmados e coleções vão no `BackupReadingStats` (campos 6 e 7), como o resto das
  estatísticas, e não em `BackupManga` 115 como o §6 previa.
- Variante só quando o texto depois do número tem palavra de variante ("edition", "cover",
  "variant"…): "#12 - The Court of Owls" é título, não variante.
- Rótulos de encadernado lêem o número depois do nome ("TPB 2"), para ordenar TPB 1 antes do 2.
- No GCD, colchetes com `;` só são equipe quando algum membro tem colchetes próprios
  ("Justice League of America [… Green Lantern [Hal Jordan] …]"); "Reverse-Flash [Professor Zoom;
  Eobard Thawne]" é um personagem com outros nomes — achado no dado real do Flashpoint #1.

**Testes na JVM (todos passando):** `IssueNumbersTest` (9: números reais das fontes, variante ×
título, anual não é o issue 2025, TPB/HC/Omnibus/Vol., frações e sufixos, especiais, ordem),
`ComicInferenceTest` (4), `ComicLibraryTest` (9: TPB não é issue, variante não conta, o mesmo issue
lido duas vezes conta uma, total desconhecido sem %, estados, próximo na ordem da série, perto de
terminar, paradas, backlog, período), `ComicSuggestionsTest` (3: Metron > GCD > ComicInfo > fonte >
inferido; confirmado não fica pendente), `CollectionRulesTest` (2), `ComicMetadataRepositoryImplTest`
(6, SQLite real: confirmar substitui por campo e escopo, sugestões por origem, biblioteca só com
favoritos e sem scanlator oculto, concluídos no período, arco atravessando séries pela data de capa,
restaurar em outro aparelho), `ReadingPlanRepositoryImplTest` (+4: **migrações 16 e 17**, anual e
encadernado depois dos issues dentro de um item, coleção avulsa sobrevive ao plano e apagá-la
encerra os planos, **um capítulo em duas coleções é lido uma vez**, coleções restauram uma vez),
`OnlineMetadataMappingTest` (10: casamento por número, rótulo confirmado, Metron série/tipos/issue e
créditos, GCD série/issues/créditos em texto/personagens e equipes/datas), `RequestLimiterTest` (3:
21º pedido espera o primeiro sair da janela, 429 segura tudo, espera longa desiste),
`ComicInfoMappingTest` (3), `BackupReadingStatsTest` (+2: ida e volta de metadados e coleções; backup
anterior sem os campos). Fixtures do Metron escritas a partir do modelo do Mokkari (sem token não
há como gravar respostas reais); as do GCD seguem respostas reais consultadas uma vez.

**Emulador** (`tools/shots/stats-c-*.png`, pt-BR e tema escuro como o app estava):
- migração 16→17 no banco do aparelho, sem erro; a aba Stats abre como antes;
- cartão "Biblioteca": 4 obras, estados, "Para ler" por formato, entraram/concluídos na semana e no
  mês — tudo "sem formato" até haver metadados; depois de confirmar o Flashpoint, "5 issues · 2
  encadernados · 40 capítulos sem formato";
- editor do Flashpoint (2011): sugestões inferidas (Issue, 2011, DC Comics pelo gênero) com a origem;
  **"Buscar online" sem token foi ao GCD** — a janela diz "Sem token do Metron…", "Enviado: só o
  nome “Flashpoint” e o ano 2011", lista a série limitada certa primeiro e dá o crédito CC BY-SA;
  escolher a série buscou 5 issues com barra de progresso (7 pedidos ao GCD no total); "Total de
  issues 5", créditos e personagens por capítulo; campo com alternativas por origem (escolhi "DC
  Comics" no lugar do "DC" do GCD) e "Aceitar 69 sugestões" não sobrescreveu a escolha;
- Biblioteca de HQ: "Flashpoint (2011) · Issue · 0 de 5 lidos" (os TPBs fora da conta);
- obra sintética na fonte local ("Stats ComicInfo Test", 3 CBZ com ComicInfo.xml inventados): item
  "Metadados de HQ" no menu da obra; "Ler ComicInfo" trouxe 35 valores (editora, selo, volume,
  total, arco); ordem #1, #2, Annual #1;
- coleção "Night Lanterns" criada a partir do arco confirmado (nome e tipo preenchidos), mais o
  Flashpoint #1 de outra obra, reordenado; "Começar um plano" abriu o plano que lê a coleção (sem
  nome nem itens editáveis), ritmo 2/semana, previsão e próximo passo no cartão; **apagar o plano
  manteve a coleção**;
- marcar Flashpoint #1–#3 como lidos: "Perto de terminar · Flashpoint (2011) · 3 de 5 · faltam 2 ·
  próximo: Issue #4" e "concluídos: 3 issues" na semana; desmarcado em seguida (o Mihon zera a
  página salva ao desmarcar: o #1 perdeu a "página 7");
- Absolute Batman: "#Annual 2025" depois do #24, "#1 Noir Edition" logo depois do #1;
- tela "Metadados online" (token do Metron, login opcional do GCD, aviso de privacidade).

**Não verificado:** Metron ao vivo (sem token — o cadastro está fechado); login do GCD; "Paradas"
(precisa de 30 dias sem leitura; coberto por teste); restaurar backup no aparelho; tema claro;
TalkBack. Arcos só aparecem com metadados que os tragam (Metron ou ComicInfo; o GCD não tem arcos).
