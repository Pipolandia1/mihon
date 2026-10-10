# Plano — Mihon no PC: app desktop e sincronização com o celular (fork Mihon)

> Plano do pedido de 09/10/2026: "ter um jeito de acessar o meu Mihon pelo PC e ler no PC, com todas
> as funções do app e continuidade entre o PC e o app do celular". Ancorado no código deste fork
> (branch `feature/bubble-zoom`, saído do upstream em `5848ffb4c`, depois do Início H13). Levantado em
> 09/10/2026 a partir de um mapa do código e de uma pesquisa externa; o que foi conferido à mão está
> marcado como tal.
>
> **Como usar:** cada passo abaixo (D0, D1, …, S1, …) é **uma sessão**, como as fases do Bubble Zoom,
> do Stats e do Início. Uma sessão implementa um passo, verifica, atualiza a seção *Status* no fim deste
> arquivo e para.

---

## 0. Resumo em uma tela

- **Um app desktop para Windows, não um emulador nem um web app** (decisão do usuário, 09/10/2026).
  Feito em **Compose Multiplatform** (JVM), que roda no Windows o mesmo tipo de interface que o app
  Android já usa.
- **A maior parte do que é seu já roda fora do Android.** O domínio do Início, do Stats, dos arcos, dos
  lançamentos e do Metron/GCD (`domain/start`, `domain/readingstats` e a camada de dados) é Kotlin puro.
  O Bubble Zoom tem 28 de 37 arquivos puros: a geometria, a ordem e a decodificação do modelo. As telas
  são Compose. O que é preciso refazer para o PC: o **leitor** (hoje feito de Views do Android), o
  **carregamento de extensões**, as **tarefas em segundo plano** e as **notificações**.
- **Extensões no PC: pelo caminho do Suwayomi.** As extensões são APKs com código Android (dex). O
  Suwayomi-Server, de código aberto, já as roda na JVM: converte o dex para jar (dex2jar) e põe por
  baixo uma camada que imita o Android (`AndroidCompat`). Ele está em dia com o Mihon: aceita as libs
  de extensão 1.4 e 1.6, as mesmas que o Mihon aceita. O código é MPL-2.0 e não é publicado como
  biblioteca, então os arquivos são **copiados para um módulo próprio**, mantendo a licença deles (§3.2).
- **Continuidade: sincronização pelo Google Drive**, numa pasta oculta que só o app vê
  (`appDataFolder`, escopo `drive.appdata`). Esse escopo é **não sensível**, e por isso o projeto no
  Google Cloud pode ser publicado "em produção" **sem verificação**. Isso evita que o login expire a
  cada 7 dias, que é o que acontece com apps em modo "teste" (§3.4).
- **Sincronizar é mesclar, nunca sobrescrever.** Cada campo leva a hora da última mudança, e o mais
  novo vence. Itens apagados deixam uma marca (*tombstone*) para não voltarem do outro aparelho. Antes
  da primeira sincronização de cada aparelho é feito um backup local automático.
- **Ordem: primeiro a sincronização** (decisão do usuário, 09/10/2026). O **marco 1** é a
  continuidade: o celular sincroniza pelo Drive (S1–S2), e o PC abre a biblioteca já sincronizada (D0,
  D1, S3). O **marco 2** é ler no PC, com extensões e leitor (D2–D3). Depois vêm as funções que faltam
  (D4–D7).
- **Sincroniza quando você termina de ler** (decisão do usuário, 09/10/2026): ao terminar um capítulo
  e ao fechar o leitor. Também sincroniza ao abrir o app, que é quando o aparelho recebe o que o outro
  leu (§3.4).
- **O upstream mudou de rumo** (conferido no repositório do Mihon em 09/10/2026). Ele converteu
  `source-api` e `source-local` de volta para módulos só-Android (#3636, 23/07/2026), sem planos de
  outras plataformas. Também removeu as colunas de sincronização (`c67a33f3d`, 27/09/2026) e refez o
  esquema do banco (#3805, 28/09/2026). E **usou as migrações 15 a 18 para outra coisa**, enquanto este
  fork usa de 15 a 24. Daí duas consequências: o desktop **não converte os módulos upstream para
  multiplataforma** (§3.1), e **o upstream fica congelado em `5848ffb4c` durante este plano** (decisão
  do usuário, 09/10/2026). Mesclar o upstream é um projeto próprio, depois (decisão 1, §6).

---

## 1. O que já existe e o desktop reaproveita

| Peça | Estado hoje | No PC |
|---|---|---|
| Domínio do Início, Stats, arcos, lançamentos (`domain/…/start`, `domain/…/readingstats`, 42 arquivos) | Kotlin puro, testado na JVM | compila como está |
| Dados (`data/`, SQLDelight) | driver `AndroidxSqliteDriver` + `sqlite-bundled`, ambos com versão JVM; os testes já rodam o esquema no `JdbcSqliteDriver` | troca só a criação do driver (arquivo em vez de `Context`) |
| Clientes Metron e GCD (`readingstats/comics/online`, 15 arquivos) | OkHttp + kotlinx-serialization; `Context` só para a pasta de cache | quase como está |
| Modelos e regras de backup (`data/backup/models`, `MangaRestorer`, criadores) | Kotlin puro; Android só na entrada (Uri, UniFile), no WorkManager e na notificação | o PC restaura um `.tachibk` do celular (D1) |
| Bubble Zoom puro (ordem, geometria, painéis, decodificador RT-DETR, 28 arquivos) | sem Android | compila como está; o ONNX Runtime tem a mesma API na JVM |
| Telas (`presentation/start` 8 arquivos sem Android; `presentation/` 205 arquivos, 79 com algo de Android) | Compose do Jetpack | Compose Multiplatform; o Android que aparece é `LocalContext` (toast, strings, `appGraph`), `BackHandler`, `Intent` |
| Strings (`i18n`, moko-resources) | único módulo multiplataforma, mas só com alvo Android | ganha o alvo `jvm()` |
| Navegação (Voyager), DI (Metro), imagens (Coil 3) | todas com artefatos para desktop | mesmas bibliotecas |
| Sincronização | **não existe**. Só restos: colunas `version`/`last_modified_at` em obras e capítulos (que o upstream já removeu) e um `isSync` sem uso no `BackupRestorer` | construída do zero (S1–S3), sem depender dessas colunas |

**As lacunas reais:**

1. **`Context` como localizador de serviços.** Há 144 chamadas a `appGraph` em 55 arquivos, e todo
   `stringResource` passa por `Context` (`core/common/.../i18n/Localize.kt` e
   `presentation-core/.../i18n/Localize.kt`). No PC entram substitutos com o mesmo nome e o mesmo
   pacote.
2. **Bibliotecas nativas só-Android no caminho principal:** `unifile`, `libarchive` (via descritor de
   arquivo), `ca.mpreg.imagedecoder`, `subsampling-scale-image-view`, `webgpuviewer`, `quickjs-android`
   e WebView (Cloudflare). Cada uma ganha um substituto JVM ou fica fora no PC (§3).
3. **O backup não serve como sincronização.** Restaurar compara a versão por obra e junta o
   "favorito" com um OU, então "tirei da biblioteca" nunca se propaga. As sessões de leitura entram
   só se o uuid for novo, então uma sessão editada não se atualiza. Histórico, categorias,
   rastreadores e todas as tabelas do fork não têm hora de modificação. E o backup não leva os avisos
   já dados (`release_notified`), então um aviso de lançamento se repetiria no outro aparelho.

---

## 2. Regras deste trabalho

As do Stats e do Início, com o que é próprio daqui:

1. **Um passo por sessão.**
2. **O app Android continua o mesmo.** Os passos D não mudam o comportamento do app no celular, e o
   `:app:assembleDebug` continua passando. Os passos S acrescentam a sincronização, desligada até o
   usuário entrar com a conta Google.
3. **Código novo em lugares próprios:**
   - módulo `desktop/`: o app do PC, com o substituto de cada peça Android num arquivo de mesmo pacote;
   - módulo `desktop-compat/`: os arquivos vindos do Suwayomi, em MPL-2.0 com o cabeçalho deles;
   - sincronização: `domain/…/tachiyomi/domain/sync/` (mescla pura), `data/…/tachiyomi/data/sync/`
     (`.sq`, leitura e escrita do retrato, cliente REST do Drive), `app/…/tachiyomi/sync/` (login,
     gatilhos e tela no Android) e `desktop/…/sync/` (o mesmo no PC).

   Um arquivo upstream recebe só chamadas curtas. Se passar de ~15 linhas, a lógica vai para o pacote.
4. **Sincronizar nunca perde dado.** Mescla campo a campo com tombstones. Antes da primeira
   sincronização, cada aparelho faz um backup local e mostra o que vai mudar ("124 obras, 3 a mais no
   PC; 812 capítulos lidos, 40 a mais no celular"), e só aplica com um "Sincronizar".
5. **Credenciais nunca sincronizam:** senha do GCD, token do Metron, tokens de rastreadores, nada
   marcado `Preference.privateKey`. Pastas e caminhos também não: cada aparelho tem os seus.
6. **Lógica pura na JVM, com testes:** regras de mescla, tombstones, retrato ↔ banco e mapeamento de
   teclas do leitor. Relógio injetado.
7. **Verificação de verdade:**
   - Android: no emulador, como sempre (`tools/shots/sync-<passo>-<n>.png`).
   - PC: captura da janela do app (`tools/desktop-shot.ps1`, criado no D0) e entrada de teclado e mouse
     (`tools/desktop-input.ps1`), nas capturas `tools/shots/desk-<passo>-<n>.png`. Uma captura que não
     mostra o esperado quer dizer passo não terminado.
   - Sincronização: sempre ida e volta entre dois aparelhos reais (emulador ↔ PC, celular ↔ PC).
8. **Privacidade:** o Drive só guarda a pasta oculta do app. O escopo `drive.appdata` não dá acesso a
   nenhum outro arquivo do Drive do usuário. Nada vai para `telemetry`.
9. **Commits:** `desktop: <descrição imperativa>` e `sync: <descrição imperativa>`, um por passo; o
   usuário commita.

---

## 3. Arquitetura

### 3.1 O módulo `desktop` compila os fontes compartilhados, sem mexer na estrutura do upstream

Há duas formas de fazer os módulos rodarem no PC:

- **(A) Converter `domain`, `data` etc. para multiplataforma.** Isso move os fontes de `src/main` para
  `commonMain`/`androidMain`, troca o plugin de cada módulo e passa a usar expect/actual. O upstream
  fez o contrário em #3636, e cada merge dele cairia em arquivos movidos e `build.gradle.kts`
  reescritos.
- **(B, a proposta) Um módulo `desktop` JVM que compila os diretórios de fonte dos módulos
  compartilhados como estão.** Ficam de fora os arquivos com Android, e cada um é substituído por um
  arquivo de mesmo pacote e nome em `desktop/src/main/kotlin`. O código compartilhado não muda de
  lugar.

**O preço do (B):**

- A IDE só "entende" um arquivo compartilhado no módulo Android, porque um diretório não pode ser de
  dois módulos no IntelliJ.
- As listas de exclusão precisam ser mantidas à mão.
- Um Android novo num arquivo compartilhado quebra só o build do PC. Por isso todo passo roda
  `:desktop:compileKotlin` além do build Android.
- O `desktop` reaplica os plugins SQLDelight (`generateAsync = true`), Metro e serialization.

**O que entra no `desktop` por (B):**

- `domain`, `data` (com o esquema `.sq`), `core-metadata` e `source-api`;
- o que é puro de `core/common`;
- de `app`: os modelos e as regras de backup, o domínio do Bubble Zoom, `readingstats` puro e
  `presentation/start`.

O `i18n` já é multiplataforma e só ganha `jvm()`.

**Pilha:**

- Compose Multiplatform **1.12.1** (estável, 22/09/2026). O 1.13 já foi alinhado ao Kotlin 2.4.20,
  mas ainda é alfa; o D0 confirma que o 1.12.1 compila com o Kotlin 2.4.20 deste build.
- Material 3 do CMP, Voyager desktop, Metro JVM, moko-resources JVM, Coil 3 (`coil-compose-jvm`,
  `coil-network-okhttp-jvm`) e `sqlite-bundled-jvm`.
- O AGP 9 não deixa o plugin multiplataforma conviver com `com.android.library`, e esse é mais um
  motivo para o `desktop` ser um módulo JVM separado.

**Onde ficam os dados no PC:**

- `%APPDATA%\Mihon\`: banco `tachiyomi.db`, preferências, cache de capas e extensões;
- downloads em `Documentos\Mihon\` (decisão 2).

### 3.2 Extensões no PC

- **O carregador**, do jeito do Suwayomi (`PackageTools.kt`):
  1. lê o manifesto do APK (`net.dongliu:apk-parser`);
  2. converte o dex para jar (`de.femtopedia.dex2jar`);
  3. corrige as referências a classes Android com ASM;
  4. carrega o jar num `ChildFirstURLClassLoader`.
- **`desktop-compat`:** os arquivos do `AndroidCompat` copiados de uma versão fixa do Suwayomi-Server
  (v2.4.2366, 30/09/2026), de onde vêm `Context`, `SharedPreferences` (as telas de configuração das
  fontes), `Handler`/`Looper`, `Uri`, `Bitmap` via ImageIO, `org.json` e `androidx.preference`. As
  classes de API do Android vêm do `android-jar` corrigido do Suwayomi.
- **Licença:** a MPL-2.0 vale arquivo a arquivo. Os copiados continuam MPL, com o fonte disponível; o
  resto do fork continua Apache-2.0.
- **O que as extensões pedem pelo Injekt:** `Application`, `NetworkHelper`, `Json`. O `desktop`
  registra esses três como o `MetroInjektRegistrar` faz no Android. O `AndroidCompat` usa Koin por
  dentro, e isso fica isolado no `desktop-compat`.
- **Repositórios de extensão:** os mesmos do celular. A lista de extensões instaladas e de repositórios
  sincroniza, e cada aparelho oferece "instalar as que faltam", sem instalar sozinho (decisão 5).
- **Fica de fora no começo:**
  - Cloudflare: o desafio precisa de um navegador de verdade (JCEF, ~150 MB). Uma fonte que cai nele
    mostra "Esta fonte pede verificação do Cloudflare; abra no celular" (decisão 6, D7).
  - Extensões que usam QuickJS: o substituto é o GraalJS, que é pesado, e entra quando uma fonte usada
    pedir.

### 3.3 O leitor no PC

Nenhuma peça visual do leitor do Android serve aqui (SSIV, ViewPager, RecyclerView, WebGPU). O leitor do
PC é **Compose**:

- **Modos:** paginado (esquerda→direita, direita→esquerda, vertical), contínuo vertical (webtoon) e
  página dupla. As preferências de leitura são as mesmas (`ReaderPreferences`).
- **Zoom e arrasto:**
  - candidato principal: **ZoomImage** (`io.github.panpf.zoomimage`, Apache-2.0), que diz fazer
    subamostragem na JVM;
  - plano B: `Modifier.zoomable` do telephoto (no desktop sem subamostragem) com a imagem reduzida à
    tela e a original carregada no zoom.

  O D3 começa medindo os dois numa página dupla grande.
- **Teclado e mouse** (decisão 7):

  | Comando | Ação |
  |---|---|
  | ←/→ (invertidas em direita→esquerda), PageUp/PageDown, espaço | mudar de página |
  | roda do mouse | passa no contínuo, muda de página no paginado |
  | Ctrl + roda | zoom |
  | F11 | tela cheia |
  | Esc | sai |
  | clique nas laterais | como os toques |

- **O que acontece ao ler:** os mesmos interactors do domínio. Grava o progresso do capítulo e a
  página, o histórico e as sessões do Stats (o `ReaderStatsRecorder` é puro), e marca para sincronizar
  ao sair do capítulo. O `ReaderViewModel` upstream (1026 linhas) não é tocado: o leitor do PC tem o
  seu.
- **Páginas:**
  - de onde vêm: da fonte (HTTP, com cache em disco), dos downloads e de pastas/arquivos locais (zip e
    cbz pela JVM; rar e 7z ficam para depois);
  - imagens animadas mostram o primeiro quadro (decisão 8);
  - o Bubble Zoom é o D6.

### 3.4 Sincronização

**Transporte: Google Drive, pasta oculta do app.**

- **Escopo** `drive.appdata`: o app vê só os arquivos que ele mesmo criou ali.
- **Um projeto no Google Cloud, criado pelo usuário com um roteiro passo a passo no S2.** Ele tem dois
  clientes OAuth:
  - **Android**: pacote `app.mihon.dev` + SHA-1 da chave do fork (§4).
  - **App de computador**: redirecionamento para `127.0.0.1` com PKCE. O Google diz que apps
    instalados "não guardam segredo", e o segredo desse cliente vai no app.
- **"Em produção" logo de cara:** com escopos só não sensíveis não há verificação, e sai a expiração de
  7 dias do modo teste. Quem publicar faz login de novo depois, porque os tokens do modo teste
  provavelmente mantêm o limite.
- **No Android:** `AuthorizationClient` do Google Identity Services (o `GoogleSignIn` é legado). Ele
  devolve um token de acesso, sem servidor.
- **Cliente REST com OkHttp, sem a biblioteca do Google:** são quatro chamadas (listar em
  `appDataFolder`, criar, atualizar e baixar), e o mesmo código serve aos dois apps.
- **A conferir no começo do S3:** se os dois clientes do mesmo projeto enxergam a mesma pasta oculta.
  O Google não diz explicitamente, mas tudo indica que sim, porque a pasta é por projeto. Se não for,
  o plano B é o escopo `drive.file` com um arquivo que os dois abrem.
- **Cuidados do Google:** o segredo do cliente aparece **uma vez só**, na criação, e cliente OAuth
  parado por 6 meses é apagado. O roteiro manda guardar o segredo junto com a chave do fork.

**O que vai no Drive:** um arquivo, `mihon-sync.pb.gz`, com o **retrato mesclado** de tudo o que
sincroniza. É um protobuf próprio (`SyncSnapshot`, em `data/sync`), e não o backup, porque precisa da
hora de cada campo e dos tombstones.

**O ciclo:**

1. Baixa o retrato e guarda a revisão.
2. Mescla com o banco local.
3. Grava o resultado local, com os gatilhos de modificação desligados durante a escrita.
4. Confere se a revisão no Drive ainda é a mesma. Se outro aparelho gravou no meio, recomeça do 1.
5. Sobe.

A escrita condicional do Drive (`If-Match`) não foi confirmada, então a conferência é feita à mão. A
janela de corrida que sobra é de segundos, entre dois aparelhos do mesmo usuário.

**Como cada aparelho sabe o que mudou e quando:** com uma tabela do fork, `sync_state`
(entidade, a chave natural em até quatro partes, o campo, `modified_at` em ms; o campo `deleted` é o
tombstone). Ela é mantida por gatilhos SQL nas tabelas upstream e nas do fork, e não pelas colunas
`version`/`last_modified_at`. Essas colunas o upstream já removeu, gravam segundos em vez de
milissegundos, e a de versão sobe a cada leitura, o que já fez outro aparelho desfazer uma mudança de
categoria no TachiyomiSY e no Suwayomi.

**Mudou no S1:** os gatilhos não vêm na migração. A migração 25 só cria as duas tabelas, vazias, e os
52 gatilhos são **instalados quando a sincronização é ligada** e removidos quando é desligada
(`SyncTriggers`, um arquivo Kotlin). Assim, sem conta Google o app escreve exatamente como antes. O que
já estava no banco antes de ligar conta como escrito no tempo 0, e num empate vence o maior valor (lido
vence não lido, a página mais adiante vence). E o mesmo arquivo serve ao app do PC. Toda a dependência
de nomes de tabela upstream fica nesse arquivo.

**O que viaja de cada capítulo:** só os capítulos com algo feito (lido, marcado, numa página, ou com
uma mudança registrada), não a lista inteira. Uma obra criada pela sincronização chega com esses
capítulos. Os outros vêm quando o aparelho atualiza a obra na fonte, e o que estava esperando por eles
é aplicado na sincronização seguinte. Capítulos nunca são acrescentados a uma obra que já existe, para
não trazer de volta os que a fonte tirou.

**Regras de mescla** (chave natural; "LWW" = o campo mais recente vence):

| O quê | Chave | Regra |
|---|---|---|
| Obra na biblioteca (favorita, data de entrada) | fonte + url | LWW; tirar da biblioteca é uma mudança como as outras (não é OU) |
| Categorias da obra | obra + nome da categoria | LWW por par; categoria apagada vira tombstone |
| Categorias (nome, ordem, flags) | nome | LWW; renomear = apagar + criar, com as obras levadas junto (decisão 9) |
| Capítulo: lido, marcado, última página | obra + url do capítulo | LWW por campo; "marcar como não lido" se propaga |
| Histórico | capítulo | última leitura = a maior; tempo lido = o maior |
| Rastreadores | obra + rastreador | LWW do progresso; o login é de cada aparelho |
| Sessões do Stats | `session_uuid` | união; editar ou apagar vale por LWW e tombstone (hoje uma sessão editada não chega ao outro lado) |
| Metas, planos, coleções, metadados confirmados, acompanhamentos (alertas), feedback do Início | as chaves que o backup já usa (uuid, fonte + url + campo, tipo + chave) | LWW e tombstone |
| Avisos já dados (`release_notified`) | tipo + chave | união, para o mesmo aviso não aparecer no PC e no celular |
| Repositórios de extensão | url | LWW e tombstone |
| Lista de extensões instaladas | pacote | união; sair da lista é tombstone. Não mora no banco: entra no S2 |
| Preferências | chave | LWW, só de uma lista fechada: leitor, biblioteca, Início, ordem das fontes das recomendações (decisão 4). Não moram no banco: entram no S2 |
| Progresso do capítulo no Stats | capítulo | as duas versões combinadas como na restauração de backup (todas as páginas vistas, a mais adiante, a primeira conclusão) |
| Início da gravação do Stats | — | o mais antigo |
| Caches (catálogo, achados nas fontes, lançamentos, arcos, metadados automáticos) | — | não sincronizam; cada aparelho refaz |

**Quando sincroniza** (decisão 3, respondida pelo usuário em 09/10/2026: sincronizar quando terminar
de ler):

- **ao terminar de ler:** quando um capítulo chega à última página e quando o leitor fecha. No
  Android, os dois eventos já passam pelo `ReaderStatsRecorder` do fork (`onPageSelected` e `close`),
  então o gatilho não mexe no leitor upstream. A sincronização roda em segundo plano e não segura a
  próxima página nem o próximo capítulo;
- **ao abrir o app:** no Android pelo Início, que é a primeira aba (como o H13 faz), e no PC ao abrir a
  janela. Sem isso, o aparelho nunca recebe o que o outro leu;
- com **"Sincronizar agora"**.

Gatilhos em sequência (terminar um capítulo e fechar o leitor logo depois) viram uma sincronização só.
Nunca no meio de um capítulo. Não há sincronização por hora nem ao ir para segundo plano: uma mudança
feita fora do leitor (adicionar uma obra, mexer em categorias) sobe na próxima destas três ocasiões.

**Relógio:** o LWW usa o relógio de cada aparelho. Com dois aparelhos do mesmo usuário e horário
automático, a diferença é de segundos. A tela de sincronização mostra a hora do outro aparelho quando
ela difere em mais de 2 minutos.

---

## 4. A chave de assinatura do fork

O usuário decidiu (09/10/2026) não trazer a `debug.keystore` do outro PC: o app do celular vai ser
reinstalado e o backup restaurado. Hoje o build assina o debug com a chave de debug **da máquina**,
então cada PC gera um APK que não instala por cima do outro. E o cliente OAuth Android do Google fica
**preso ao SHA-1 da chave**. Por isso o S2 começa criando a **chave do fork**, que é onde ela passa a ser necessária:

- `mihon-fork.jks` fora do repositório (`D:\android-build-tools\signing\`), mais o
  `keystore.properties` na raiz (o build já lê esse arquivo e o git já o ignora). `*.jks` entra no
  `.gitignore`, porque hoje não está.
- O usuário guarda o `.jks` e a senha num lugar seguro (gerenciador de senhas ou Drive). **Outro PC só
  gera um APK compatível com o arquivo e a senha.**
- **No celular, antes de instalar o primeiro APK com a chave nova:** fazer um backup no app atual,
  desinstalar, instalar o novo e restaurar. O backup leva o Stats, o Início, os arcos e os
  metadados confirmados (campo 9001). Os caches são refeitos.

---

## 5. Passos

Cada passo: **arquivos**, **testes**, **verificação**, **aceite**. ⬆ marca arquivo upstream, que
precisa de aprovação antes de editar.

**S1 — O motor da sincronização** (sem rede e sem tela).
- **Arquivos:**
  - `domain/…/sync/`: modelo do retrato, regras de mescla (§3.4) e tombstones;
  - `data/…/sync/`: `sync_state.sq` e migração 25 (as duas tabelas), os gatilhos (`SyncTriggers`,
    instalados ao ligar), leitura e escrita retrato ↔ banco, e o formato do arquivo do Drive.
- **Testes** (JVM, banco de teste em JDBC), um por cenário:
  - lido num aparelho e desmarcado no outro depois;
  - tirada da biblioteca de um lado e lida do outro antes;
  - sessão apagada; categoria renomeada;
  - os dois aparelhos sem mudanças → retrato idêntico, nada sobe;
  - 10 mil capítulos em menos de 2 s.
- **Verificação:** o app Android abre com a migração 25 sobre um banco de verdade (o do emulador), e
  os gatilhos marcam em `sync_state` um capítulo lido e uma obra tirada da biblioteca (o banco puxado
  do emulador e lido pelo `sqlite3` do platform-tools). O desktop passa pela mesma migração no D1.
- **Aceite:** nenhum cenário perde dado, e mesclar A com B dá o mesmo que mesclar B com A.

**S2 — Google Drive no celular.**
- **Antes de tudo:**
  - a chave do fork (§4): `mihon-fork.jks` e `keystore.properties`; `.gitignore` ⬆ (`*.jks`);
  - o usuário faz um backup no app atual do celular, desinstala, instala o APK com a chave nova e
    restaura;
  - o usuário entra com a conta Google no emulador (Configurações do Android), para os dois aparelhos
    sincronizarem entre si.
- **Arquivos:**
  - roteiro do Google Cloud para o usuário (projeto, tela de consentimento, publicar, os dois clientes;
    o do PC já fica pronto para o S3);
  - `AuthorizationClient`; cliente REST; tela **Sincronização** (conta, "Sincronizar agora", última
    vez, o que mudou e o registro);
  - gatilhos (§3.4): ao terminar de ler, pelo `ReaderStatsRecorder`, e ao abrir, pelo Início (os dois
    do fork); backup antes da primeira vez;
  - um item em Configurações ⬆.
- **Testes:** cliente REST contra respostas gravadas do Drive; a conferência da revisão (outro
  aparelho gravou no meio → recomeça); gatilhos agrupados (terminar um capítulo e fechar o leitor logo
  depois → uma sincronização só).
- **Verificação:**
  - `apksigner verify --print-certs` mostra o SHA-1 da chave do fork, o mesmo cadastrado no Google;
  - emulador e celular, cada um com uma mudança diferente → os dois convergem;
  - terminar um capítulo no emulador → o celular, ao abrir o app, mostra o capítulo lido;
  - o arquivo existe na pasta oculta (listado pela API).
- **Aceite:** a primeira sincronização mostra o resumo e só aplica com o toque, e o app sem conta
  continua igual ao de hoje. Ao fim do S2 o celular já guarda tudo no Drive, mesmo antes de o app do
  PC existir.

**D0 — Fundação do app do PC.**
- **Arquivos:**
  - módulo `desktop/` com o plugin Compose Multiplatform 1.12.1; `settings.gradle.kts` ⬆ (`include`);
    `gradle/libs.versions.toml` ⬆ (plugin e bibliotecas do desktop);
  - `i18n/build.gradle.kts` ⬆ (`jvm()`);
  - uma janela "Mihon" com o tema do app e as strings do `i18n`;
  - `tools/desktop-shot.ps1` e `tools/desktop-input.ps1`.
- **Testes:** nenhum de lógica; o build é o teste.
- **Verificação:** `./gradlew :desktop:run` abre a janela, e a captura mostra um texto pt-BR vindo do
  `i18n`; o `:app:assembleDebug` continua passando.
- **Aceite:** o app Android não muda em nada, e o build do desktop leva menos de um minuto depois do
  primeiro.

**D1 — O núcleo no PC e a biblioteca de um backup.**
- **Arquivos:**
  - `desktop` passa a compilar `domain`, `data`, `core-metadata` e os modelos e regras de backup (§3.1),
    com os substitutos:
    - `StorageManager`, `PreferenceStore` (arquivo em `%APPDATA%`), `Localize` (moko na JVM) e o
      driver do banco;
    - o grafo Metro do desktop (`DesktopGraph`);
  - na tela: Biblioteca (capas pelo Coil, categorias em abas, busca, ordem) e "Restaurar backup"
    (escolher um `.tachibk`).
- **Testes:**
  - restaurar o backup de teste na JVM;
  - toda exclusão da lista de (B) tem um substituto, conferido por um teste que compila.
- **Verificação:** o usuário passa um backup do celular, e a biblioteca no PC tem as mesmas obras e
  categorias e os mesmos capítulos lidos, contados no banco dos dois lados.
- **Aceite:** abrir o app com 500 obras leva menos de 2 s até a primeira tela. Capas de fontes que
  pedem cabeçalhos podem aparecer vazias até o D2.

**S3 — Sincronização no PC. Marco 1: continuidade.**
- **Arquivos:** login no navegador (127.0.0.1 + PKCE), o mesmo motor e cliente, a mesma tela de
  Sincronização; gatilho ao abrir a janela e "Sincronizar agora". O "ao terminar de ler" do PC entra
  com o leitor, no D3.
- **Começa conferindo:** se os dois clientes enxergam a mesma pasta oculta, um teste de 5 minutos. Se
  não, o plano B da §3.4.
- **Verificação:** ler até a página 12 no celular e sair do leitor → abrir o PC → a contagem de não
  lidos da obra na Biblioteca acompanha, e o banco do PC tem a página do capítulo. E o contrário:
  tirar uma obra da biblioteca no PC → some do celular depois de sincronizar, e não volta.
- **Aceite:** as duas idas e voltas acima, com capturas dos dois lados.

**D2 — Extensões no PC e Navegar.**
- **Arquivos:**
  - `desktop-compat` (o `AndroidCompat` copiado), o carregador (§3.2) e o `NetworkHelper` da JVM
    (cookies em arquivo, sem Cloudflare);
  - repositórios, instalar, atualizar e remover;
  - Navegar: fontes, populares, recentes, busca e busca global;
  - página da obra e lista de capítulos, adicionar à biblioteca.
- **Testes:** ler o manifesto de um APK de teste e as regras de confiança (as mesmas do
  `TrustExtension`).
- **Verificação:** as fontes que o usuário usa no celular (a lista sai do backup) são instaladas no
  PC, buscam "Absolute Batman" e abrem a obra e os capítulos; as capas que faltaram no D1 aparecem.
- **Aceite:** toda fonte usada abre, ou mostra o motivo de não abrir (Cloudflare ou JS) numa
  frase.

**D3 — O leitor no PC. Marco 2: ler no PC.**
- **Arquivos:**
  - leitor Compose (§3.3): modos, zoom, teclado e mouse, tela cheia, página dupla e pré-carregamento
    de 2 páginas;
  - progresso, histórico e sessões do Stats gravados como no celular;
  - a sincronização ao terminar de ler (fim do capítulo e fechar o leitor), com a mesma regra do
    celular.
- **Testes:** mapa de teclas por direção de leitura; página dupla (qual página vai de que lado);
  progresso e sessão ao sair no meio.
- **Verificação:**
  - um capítulo real lido até o fim, nos três modos, com capturas;
  - a página 12 ao sair → reabre na 12;
  - a sessão aparece no banco com a duração certa (pelo log);
  - terminar um capítulo no PC → o celular, ao abrir o app, mostra o capítulo lido e continua no
    próximo.
- **Aceite:** trocar de página em menos de 100 ms com a próxima já carregada, medido no log, e
  nenhuma travada ao passar páginas de 4000 px.

**D4 — Biblioteca completa e downloads.** Filtros, ordens, ações em lote (marcar lido, categorias).
Baixar capítulos para ler offline (fila, pasta, `.cbz`). Atualizar a biblioteca (botão, e agendada
enquanto o app está aberto). Novidades e Histórico.

**D5 — Início, Stats, arcos, lançamentos e metadados no PC.** As telas portadas, com o domínio já
rodando desde o D1. Metron e GCD com a pasta de cache do PC. Avisos pela bandeja do Windows
(`Tray.sendNotification`) e a checagem diária enquanto o app está aberto, sem repetir o que o celular
já avisou (`release_notified` sincroniza).

**D6 — Bubble Zoom no PC.** ONNX Runtime para JVM (CPU, decisão 11; o pacote leva só o binário win-x64),
a página decodificada para o modelo pela JVM, e o cartão e a página em Compose. Espaço/→ andam de
balão em balão, e o fim da página segue para a próxima. As mesmas páginas sintéticas
(`tools/test-pages/`) com coordenadas conhecidas.

**D7 — Acabamento.** Rastreadores (login pelo navegador), Cloudflare com JCEF, backup e restauração
pelo menu, as configurações que faltam, instalador para Windows (pasta do app via `createDistributable`,
depois MSI) e atualização do app do PC.

---

## 6. Decisões em aberto (propostas padrão entre parênteses)

> Respondidas pelo usuário em 09/10/2026: **1** (congelar o upstream), **3** (sincronizar ao terminar
> de ler), **6** (Cloudflare fora até o D7) e **12** (sincronização primeiro). As demais seguem com a
> proposta padrão e são confirmadas no passo que precisa delas.

1. **Trazer o upstream daqui para frente?** O upstream andou 93 commits desde `5848ffb4c`: refez o
   esquema (`mangas` → `manga`, colunas com prefixos), tirou as colunas de sincronização e numerou como
   15–18 migrações que no fork são outras. Mesclar exige uma migração de reconciliação do banco e
   refazer os 37 arquivos upstream que o fork mudou. (Congelar no `5848ffb4c` durante este plano e
   tratar a mesclagem como um projeto próprio depois.)
2. **Dados e downloads no PC:** (`%APPDATA%\Mihon` e `Documentos\Mihon`).
3. **Quando sincronizar:** respondida — ao terminar de ler (fim do capítulo e fechar o leitor), mais ao
   abrir o app e com o botão (§3.4).
4. **Preferências que sincronizam:** (só leitor, biblioteca, Início e a ordem das fontes das
   recomendações; nunca credenciais nem pastas.)
5. **Extensões:** (sincronizar a lista e oferecer "instalar as que faltam", sem instalar sozinho.)
6. **Cloudflare no PC:** (fora até o D7; a fonte avisa "abra no celular".)
7. **Teclas do leitor:** (as da §3.3, configuráveis depois.)
8. **Imagens animadas no leitor do PC:** (primeiro quadro.)
9. **Categoria renomeada:** (apagar + criar, levando as obras.)
10. **Sessões de leitura com o aparelho de origem** ("PC" ou "celular") para o Stats mostrar onde
    você lê? (não agora; a coluna pode entrar no S1 sem tela, para não precisar de outra migração.)
11. **Bubble Zoom no PC pela CPU?** (sim; DirectML exigiria compilar o ONNX Runtime para Java.)
12. **Ordem dos passos:** respondida — sincronização primeiro: S1 → S2 → D0 → D1 → S3 → D2 → D3 → D4
    → D5 → D6 → D7.

---

## 7. Arquivos upstream que o plano toca

| Arquivo | Passo | Por que não dá para ficar no pacote |
|---|---|---|
| `settings.gradle.kts` | D0 | é onde os módulos `desktop` e `desktop-compat` entram no build |
| `gradle/libs.versions.toml` | D0+ | catálogo de versões do projeto (plugin CMP e bibliotecas do desktop) |
| `i18n/build.gradle.kts` | D0 | o alvo `jvm()` é declarado no próprio módulo |
| `.gitignore` | S2 | `*.jks` |
| `data/…/migrations/25.sqm` + `sync_state.sq` | S1 | arquivos novos. Os gatilhos (`data/…/sync/SyncTriggers.kt`, instalados ao ligar a sincronização) citam tabelas upstream (`mangas`, `chapters`, `history`, `categories`, `mangas_categories`, `manga_sync`, `extension_store`) |
| item "Sincronização" na lista de Configurações | S2 | a lista é upstream; o item chama uma tela do pacote |

Nenhum outro arquivo upstream muda. O app Android, o leitor do Android, o Bubble Zoom do Android e o
backup ficam como estão. O desktop compila os fontes compartilhados sem alterá-los.

## 8. Riscos

- **Tamanho.** É o maior projeto do fork até aqui, e o D2 (extensões) e o D3 (leitor) são os mais
  incertos. Se o D2 mostrar que as fontes usadas não rodam no PC, o plano para ali e volta para
  decisão, antes de investir no resto. Com a sincronização primeiro, o que já estiver feito continua
  valendo: o celular sincroniza pelo Drive e o PC mostra a biblioteca e o progresso.
- **Versões no mesmo build:** CMP 1.12.1, Kotlin 2.4.20, AGP 9.4 e o BOM alfa do Compose. O D0 existe
  para descobrir isso cedo.
- **Fontes com Cloudflare ou JS** não funcionam no PC até o D7.
- **O upstream divergente** (decisão 1). Os gatilhos de sincronização citam nomes de tabelas que o
  upstream já trocou.
- **Google:** o segredo do cliente aparece uma vez só, um cliente parado por 6 meses é apagado, e o
  compartilhamento da pasta oculta entre clientes ainda está por confirmar (S3).
- **Relógio dos aparelhos:** o LWW depende de horário automático nos dois.
- **Disco do PC:** o D: é um HD mecânico. O emulador já foi para o SSD (09/10/2026), e se o desktop
  ficar lento, os dados dele já moram no `%APPDATA%`, que fica no C:.

## Fontes consultadas (09/10/2026)

- Upstream (conferido com `git fetch` e `git log`): `9c3cc1ca5` (#3636), `c67a33f3d`, `93cc07511`
  (#3805); migrações 15–18 do upstream.
- Suwayomi-Server: <https://github.com/Suwayomi/Suwayomi-Server>, `PackageTools.kt`, `AndroidCompat/`,
  releases v2.3.2243 e v2.4.2366; MPL-2.0 FAQ: <https://www.mozilla.org/en-US/MPL/2.0/FAQ/>.
- Google: escopos do Drive <https://developers.google.com/workspace/drive/api/guides/api-specific-auth>;
  modo teste e 7 dias <https://support.google.com/cloud/answer/15549945>; verificação para escopos não
  sensíveis <https://support.google.com/cloud/answer/13463073>; apps instalados
  <https://developers.google.com/identity/protocols/oauth2/native-app>; autorização no Android
  <https://developer.android.com/identity/authorization>.
- TachiyomiSY (sync por backup e os problemas abertos, #1624–#1639):
  <https://github.com/jobobby04/TachiyomiSY>.
- Compose Multiplatform: <https://github.com/JetBrains/compose-multiplatform/releases>; AGP 9 e KMP
  <https://kotlinlang.org/docs/multiplatform/multiplatform-project-agp-9-migration.html>; distribuição
  <https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html>.
- ZoomImage <https://github.com/panpf/zoomimage>; telephoto (sem subamostragem no desktop).

---

## Status

- [x] S1 motor da sincronização — `domain/…/sync/` (`SyncSnapshot`, `SyncEntity`, `SyncMerge`, `SyncProgress`,
  `SyncChanges`, `SyncRepository`), `data/…/sync/` (`SyncTriggers`, `SyncSnapshotReader`, `SyncSnapshotWriter`,
  `SyncRepositoryImpl`, `SyncSnapshotCodec`, `SyncValues`), `sync_state.sq`, migração 25
- [x] S2 Google Drive no celular — chave do fork, `DriveFiles` e `DriveSync` (dados, compartilhados com o
  PC), `SyncPreferences`/`SyncLog`, `GoogleDriveAuth`, `SyncRunner`, `SyncScheduler`, tela Sincronização em
  Mais, gatilhos no `ReaderStatsRecorder` e no Início; roteiro `docs/SYNC_GOOGLE_SETUP.md` e política
  `docs/SYNC_PRIVACY.md`
- [ ] D0 fundação do app do PC: módulo `desktop`, `i18n` com `jvm()`, janela
- [ ] D1 núcleo no PC e biblioteca de um backup
- [ ] S3 sincronização no PC — **marco 1: continuidade**
- [ ] D2 extensões no PC e Navegar
- [ ] D3 leitor no PC — **marco 2: ler no PC**
- [ ] D4 biblioteca completa e downloads
- [ ] D5 Início, Stats, arcos, lançamentos e metadados no PC
- [ ] D6 Bubble Zoom no PC
- [ ] D7 acabamento

### Verificação do S1 (09/10/2026)

**Como ficou:**
- **O que sincroniza:** 17 tipos de registro (`SyncEntity`): obras, capítulos, histórico, categorias, categorias
  das obras, rastreadores, repositórios de extensão, sessões e progresso do Stats, início da gravação, metas,
  coleções, planos, metadados confirmados, acompanhamentos, avisos já dados e feedback do Início. Cada um pela
  chave natural, nunca pelo id local. Preferências e a lista de extensões instaladas não moram no banco e
  ficaram para o S2.
- **Mescla** (`SyncMerge`): campo a campo, a mudança mais recente vence. Num empate vence o maior valor (lido
  vence não lido; números pelo valor, então a página 10 vence a 9). O tempo lido do histórico fica com o
  maior, o início da gravação com o mais antigo, e o progresso do Stats com as duas versões combinadas
  (`ChapterPages.merge`, a mesma da restauração de backup). Um tombstone derruba tudo o que foi escrito antes
  dele, e o que vem depois traz o registro de volta. A ordem não importa (A com B = B com A), e mesclar
  consigo mesmo não muda nada.
- **Gatilhos** (`SyncTriggers`, 52): instalados por `enable()` e removidos por `disable()`. Ficam quietos
  enquanto uma mescla é escrita (`sync_control.applying`). Uma inserção só registra o que difere de uma linha
  nova, então buscar a lista de capítulos não parece "marcar tudo como não lido". O que some em cascata (os
  capítulos de uma obra apagada) não vira tombstone. Uma linha recriada no mesmo milissegundo em que foi
  apagada (Mihon refaz as categorias de uma obra assim) apaga o próprio tombstone.
- **Escrita da mescla** (`SyncSnapshotWriter`): numa transação só, por entidade (categorias e obras antes do
  que depende delas). O que não dá para escrever ainda espera no arquivo (`pending`): o capítulo de uma obra
  que este aparelho não atualizou, ou uma sessão que o leitor ainda está gravando aqui. Capítulos só são
  criados junto com uma obra criada pela sincronização. Os detalhes de uma obra (`info`) só servem para
  criá-la. Depois da escrita, qual sessão concluiu primeiro cada capítulo tocado é refeito sobre as sessões
  dos dois aparelhos.
- **Arquivo do Drive** (`SyncSnapshotCodec`): protobuf com gzip, versão de formato, quem escreveu e quando.
  Registros em ordem fixa, então o mesmo retrato dá sempre os mesmos bytes. Tipos desconhecidos (de um app
  mais novo) são ignorados, e um formato mais novo é recusado.

**Mudou em relação ao plano:** os gatilhos ficam fora da migração (§3.4), e preferências e extensões
instaladas foram para o S2. Para os 10 mil capítulos em menos de 2 s, a primeira versão levou 4,3 s na JVM: a
escrita fazia três comandos por capítulo, e cada atualização de capítulo ainda dispara os gatilhos antigos do
Mihon. Com os capítulos atualizados em lotes de 500 (`WHERE _id IN`) e os ids lidos uma vez por obra, a
escrita caiu de 3.992 ms para 518 ms, e a sincronização inteira ficou em ~0,7 s.

**Testes na JVM:** `SyncMergeTest` (8), `SyncScenariosTest` (13, dois bancos sincronizando por um arquivo, com
relógio fixo), `SyncMigrationTest` (1), `SyncSnapshotCodecTest` (2). Domínio 310 e dados 80, todos passando;
spotless limpo.

**Emulador** (`tools/shots/s1-*.png`):
- O banco do emulador estava na versão 25, sem as tabelas. Instalado o APK, o app abriu a frio em 6,7 s, o
  banco passou à **versão 26** com `sync_state` e `sync_control` (`0, 0, null, 0`) e **nenhum gatilho**: com a
  sincronização desligada, nada é registrado.
- Os 52 gatilhos foram instalados no banco do emulador com o SQL que `enable()` executa (sqlite3 3.44.3 do
  Android), e o arquivo manteve o dono e o rótulo SELinux do app.
- No app, com a fonte local ("Sync Test", 2 capítulos):
  - abrir a obra registrou só os detalhes (`info`, 23:02:34), e nada dos capítulos não lidos;
  - adicionar à biblioteca registrou `favorite` e `date_added` (23:02:56);
  - marcar o capítulo 1 como lido registrou `read` (23:03:15) e o progresso do Stats (12 ms depois);
  - tirar da biblioteca registrou `favorite` e `date_added` de novo (23:03:27);
  - o capítulo 2 não aparece. Nenhum crash.
- O banco puxado do emulador, lido pelo `SyncSnapshotReader` na JVM, deu exatamente esse retrato, e o arquivo
  dele tem 604 bytes e volta igual.

**Não verificado no aparelho:** a mescla escrita no emulador (só na JVM, com bancos SQLite reais, até o S2,
que é quando existe um segundo aparelho e o Drive).

**Dados de teste no emulador:** onboarding feito (pasta `/storage/emulated/0/Mihon`); a obra "Sync Test" na
fonte local, fora da biblioteca, com o capítulo 1 lido; os gatilhos versão 1 instalados no banco do app.

### Verificação do S2 (10/10/2026)

**Como ficou:**
- **Chave do fork:** `mihon-fork.jks` fora do repositório (`D:\android-build-tools\signing\`), senha só no
  `keystore.properties` (ignorado pelo git; `*.jks` e `*.keystore` também). O APK debug sai assinado com
  ela (SHA-1 `88:93:4E:…:42:B6`), a mesma cadastrada no cliente Android do Google.
- **Google Cloud** (feito pelo usuário com `docs/SYNC_GOOGLE_SETUP.md`): projeto "Mihon Sync", Drive API,
  escopo só `drive.appdata`, clientes Android e de computador. Para sair de "Teste", o Google exige página
  inicial e política de privacidade: a política é `docs/SYNC_PRIVACY.md`, publicada no GitHub.
- **Drive** (`DriveFiles`, no módulo de dados, para o PC usar o mesmo): quatro chamadas REST com OkHttp, sem a
  biblioteca do Google. 401 vira `Unauthorized` e um token novo é pedido uma vez.
- **O ciclo** (`DriveSync`): baixa, mescla e escreve aqui, e sobe só se o arquivo mudar. Antes de subir,
  confere a versão do arquivo; se outro aparelho escreveu no meio, recomeça (até 3 vezes, depois desiste sem
  sobrescrever). Duas cópias criadas ao mesmo tempo são mescladas numa só.
- **No Android:** login pelo `AuthorizationClient` (`GoogleDriveAuth`, sem client id no código); o
  `SyncRunner` faz o backup completo antes da primeira sincronização
  (`autobackup/mihon_before_sync_<data>.tachibk`), liga os gatilhos e guarda o registro das últimas 20. O
  `SyncScheduler` agrupa os pedidos: ao abrir (Início, 3 s) e ao terminar de ler (última página ou fechar o
  leitor, 10 s, pelo `ReaderStatsRecorder`).
- **Tela Sincronização** (Mais, depois de Dados e armazenamento): conectar; antes da primeira, o resumo do
  que muda aqui e do que vai para o Drive, e só aplica com o toque; depois, conta, última sincronização,
  "Sincronizar agora", "Desconectar" e o registro.

**Mudou em relação ao plano:** o item fica na aba Mais (arquivo upstream já tocado no H1), e não na lista
de Configurações, que nunca tinha sido tocada. A sincronização ao ir para segundo plano saiu (decisão 3).

**Testes na JVM:** `DriveFilesTest` (5, contra respostas no formato do Drive), `DriveSyncTest` (5: o
primeiro cria e o segundo recebe; outro aparelho escreve no meio → recomeça; duas cópias viram uma; desiste
em vez de sobrescrever; a prévia não escreve nada), `SyncLogTest` (2). Domínio 312 e dados 90, todos
passando; spotless limpo. O teste "outro aparelho escreve no meio" passou de 1 min uma vez, com o build do
APK e o emulador disputando a memória; sozinho leva 1,8 s.

**Emulador e celular** (`tools/shots/s2-*.png`), com a biblioteca real do usuário (backup do celular):
- backup restaurado no emulador: 75 obras na biblioteca, 304 capítulos lidos, 299 no histórico, 6
  categorias, 7 sessões;
- conectar: o Google reconheceu o app (pacote + chave) e pediu a conta; o usuário autorizou;
- primeira sincronização (16:50): backup de 182 KB antes; **11.174 registros** enviados em 5,4 s, nada
  mudou no emulador;
- ao reabrir o app (16:51): sincronizou sozinho em 3 s, "nada novo";
- marcador no #1 de *Absolute Batman: Ark M Special* + "Sincronizar agora" (16:52): **1** registro enviado;
- celular: reinstalado com a chave do fork, backup restaurado, conectado e sincronizado; o emulador depois
  disso não achou nada novo (o celular tinha o mesmo backup);
- **ida e volta:** o usuário tirou o marcador no celular e sincronizou; o emulador (17:01) recebeu
  `CHAPTER changed=1`, o marcador ficou 0, com a hora do celular (17:00:41).

**Não verificado no aparelho:** a sincronização ao terminar um capítulo no leitor (no emulador só há a
fonte local de teste, que entraria nos dados reais; fica para o uso normal no celular, conferindo o
registro da tela); a escrita concorrente de dois aparelhos (coberta pelo teste); o app publicado "Em
produção" (o usuário configurou; se ficou em "Teste", o Google pede para conectar de novo a cada 7 dias).

**Dados no emulador:** a biblioteca real do usuário, conectada à conta dele e sincronizando; a obra de teste
"Sync Test" ficou só como arquivos na pasta `local/`, fora do banco.
