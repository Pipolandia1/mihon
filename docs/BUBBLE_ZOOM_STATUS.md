# Bubble Zoom — Status

> Atualizar ao fim de cada fase. Este arquivo é o que substitui o contexto de conversas
> antigas com o agente. Mantenha curto e honesto.

## Fase atual

P9 concluída. **E2 em andamento** — emenda do usuário: o card é a única apresentação
(a câmera da P4 sai), com pixels em resolução nativa, e o gesto é o do Play Books
(duplo-toque ativa, swipe anda, duplo-toque desativa). Depois: **P10**.

Ordem executada até aqui: P0 → P1 → P2 → P3 → P4 → P5 → P6 → **P16** → **E1** → P7 → P8 → P9
→ **E2**.

> **Ordem alterada pelo usuário (19/09/2026):** a **P16 (overlay visual / card do balão)**
> sobe para logo depois da **P6**, antes de P7–P15. Motivo: a P4 move a câmera, mas a
> experiência que define a feature é o balão recortado como card, e isso é a P16. Julgá-la
> só no fim significaria descobrir tarde demais se o card não presta. Depois do P6 dá para
> percorrer os três balões fixos com o dedo e sentir o resultado real antes de investir em
> detector, cache e ordenação.
>
> Ordem nova: P5 → P6 → **P16** → P7 → P8 → P9 → … → P15 → P17 → P18.
>
> **Decisão do usuário para a P16 (19/09/2026): sem scrim.** O card do balão é erguido sobre
> a página, mas o resto da página **continua visível**, sem escurecer. O prompt da P16 fala em
> "scrim" ao tratar do tema claro/escuro; essa parte não se aplica.

> O `docs/BUBBLE_ZOOM_ARCHITECTURE.md` precisa da sua revisão manual antes da P2.
> Todas as fases seguintes dependem dele.

## Ambiente

- OS: Windows 10, Claude Code no app de desktop (Bash via Git Bash)
- Repo: `D:/mihon` — branch `feature/bubble-zoom` (movido para fora do OneDrive)
- Android SDK: `D:/Android/Sdk` — JDK: `D:/Java/jdk-21.0.12.1+1` (Temurin 21)
- Saída de build: `D:/mihon/build` (normal; o redirecionamento do P0 foi desfeito)
- Configuration cache ligado: incremental de 9m31s caiu para **4s**
- AVD: `mihon_test` — Pixel 7, Android 16 / API 36, x86_64, 4 GB RAM, GPU host
- Aceleração: driver AEHD (`sc query aehd`)
- `applicationId` de debug: `app.mihon.dev`
- Screenshots: `tools/shots/` — páginas sintéticas: `tools/test-pages/`

## Concluído

- [x] P0  setup: toolchain, emulador, baseline compilando, manga sintético de teste
- [x] P1  mapa técnico do reader (docs/BUBBLE_ZOOM_ARCHITECTURE.md)
- [x] P2  BubbleBox / BubbleDetection / BubblePageResult + testes
- [x] P3  debug overlay com 3 boxes hardcoded
- [x] P4  focusOnBubble animado
- [x] P5  BubbleZoomController + máquina de estados
- [x] P6  integração com moveToNext / moveToPrevious
- [x] P7  preferência Bubble Zoom (off = vanilla)
- [x] P8  interface BubbleDetector + FakeBubbleDetector
- [x] P9  detector ONNX real + pipeline de imagem
- [ ] P10 confidence filtering + BubbleDetectionConfig
- [x] P11 prefetch (metade feita; o cache em disco é a P13)
- [x] P12 BubbleOrderResolver (ordem de leitura sem detectar painéis — ver abaixo)
- [ ] P13 cache em disco com modelVersion
- [ ] P14 teclas de volume — absorvida pela E2 (já andam pelo gancho da P6)
- [ ] P15 double tap — é a E2
- [x] P16 BubbleZoomOverlayView  (antecipada, feita logo após a P6)
- [ ] P17 debug HUD
- [ ] P18 dataset de 30 páginas + tuning

## Arquivos upstream modificados

- `.gitignore` — acrescentadas as entradas do Bubble Zoom (shots, test-pages, `*.onnx`, `*.tflite`).
- `ui/reader/viewer/pager/PagerViewer.kt` — **P6, aprovado.** Desvio único de navegação em
  `moveRight`/`moveLeft`, `activatePage` no `onReaderPageSelected`, `deactivate` na transição
  de capítulo.
- `ui/reader/viewer/pager/PagerPageHolder.kt` — **P6, aprovado.** Coleta o `StateFlow` e
  aplica o foco da própria página; cancela no `onDetachedFromWindow`.
- `ui/reader/viewer/ReaderPageImageView.kt` — **P3, aprovado pelo usuário.** 7 linhas
  funcionais: anexa o overlay no caminho não-animado, remove no animado, e repassa
  `onScaleChanged`/`onCenterChanged`/`onImageLoaded` como `invalidate()`. Nenhuma lógica
  de Bubble Zoom mora aqui.
- `gradle.properties` e `gradle/build-logic/gradle.properties` — `org.gradle.configuration-cache=true`.

**Esta lista está desatualizada desde a P6 e é mantida só como histórico.** O que vale é a
medição contra o último commit upstream (`47fb48d2f`), refeita ao fim da P9:

| arquivo | linhas + | linhas - |
|---|---|---|
| `build.gradle.kts` | +3 | -0 |
| `SettingsReaderScreen.kt` | +12 | -0 |
| `ReadingModePage.kt` | +17 | -0 |
| `ReaderPreferences.kt` | +17 | -0 |
| `ReaderPageImageView.kt` | +27 | -1 |
| `PagerPageHolder.kt` | +71 | -0 |
| `PagerViewer.kt` | +47 | -3 |
| `libs.versions.toml` | +2 | -0 |
| `strings.xml` | +5 | -0 |

A regra do CLAUDE.md é ~15 linhas por classe upstream; `PagerPageHolder` e `PagerViewer`
estão muito acima, e cada fase desde a P7 registra o número em vez de escondê-lo.

## Arquivos novos

- `CLAUDE.md`, `.claude/settings.json`, `docs/` — copiados do kit, com o `CLAUDE.md` corrigido.
- `tools/start-emulator.ps1` — sobe o AVD num terminal próprio do usuário.
- `tools/make-test-pages.py` — gera as 5 páginas sintéticas e o `ground-truth.json`.
- `tools/push-test-manga.ps1` — empacota e envia para o local source do emulador.
- `tools/shots/baseline/` — linha de base de comparação para todas as fases (gitignored).

- `docs/BUBBLE_ZOOM_ARCHITECTURE.md` — mapa técnico do reader (fase P1).
- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/bubblezoom/` — `BubbleBox`, `BubbleType`,
  `BubbleDetection`, `BubblePageResult` (fase P2). Kotlin puro, **zero imports**.
- `app/src/test/java/eu/kanade/tachiyomi/ui/reader/bubblezoom/` — 44 testes JVM.
- `bubblezoom/BubbleZoomDebugOverlay.kt` — overlay de depuração (fase P3), com o
  `const val BUBBLE_ZOOM_DEBUG = true` que o desliga.
- `bubblezoom/BubbleZoomOverlayView.kt`, `BubbleCardGeometry.kt`, `BubbleZoomDebugBoxes.kt` —
  o card erguido (P16). A geometria é Kotlin puro com testes JVM.
- `bubblezoom/BubbleZoomPagePresenter.kt` — despachante card/câmera (P6 + P16), e a extensão
  `ReaderPage.bubblePageKey()`.
- `bubblezoom/BubbleZoomState.kt` e `bubblezoom/BubbleZoomController.kt` — máquina de estados
  (P5), Kotlin puro, 28 testes JVM. Zero linhas upstream.
- `bubblezoom/BubbleZoomConfig.kt` e `bubblezoom/BubbleZoomFocus.kt` — foco animado (P4).
  `focusOnBubble` e `resetBubbleZoom` são extension functions sobre a image view, então a
  P4 saiu com **zero linhas upstream**.

O redirecionamento de build do P0 (init script + junction `.gradle`) foi **removido** ao
mover o repositório para fora do OneDrive. `D:/mihon-build` ficou órfão com ~1 GB e pode
ser apagado.

## Decisões tomadas

- Runtime de ML: ONNX Runtime Mobile (decidido no plano; revisar se a latência não fechar em P9).
- Modelo: **Kiuyha/Manga-Bubble-YOLO yolo26n** (P9), Apache-2.0 na ficha, carimbo AGPL da ultralytics no arquivo — ver a seção da P9.
- modelVersion atual: **1** para o detector ONNX (yolo26n a 640); **-1** reservado para o `FakeBubbleDetector`. Um modelo real usa
  valor positivo, para que o cache da P13 nunca sirva caixa de debug em página real.
- SDK e JDK fora do OneDrive; código-fonte dentro (escolha do usuário).

## Correções ao plano original descobertas no P0

- JDK é **21**, não 17 (`.github/.java-version`).
- **Não existe flavor `standard`.** As tasks são `:app:compileDebugKotlin` e `:app:assembleDebug`.
- O build faz **split por ABI**: não há `app-debug.apk` único.
- `compileSdk = 37.1`, `targetSdk = 36`, `minSdk = 26`, NDK `29.0.14206865` obrigatório.
- O local source **não** tem diretório fixo: depende de uma pasta escolhida via SAF no onboarding.
- **O modo de leitura padrão é RTL** — a zona esquerda avança.

## Problemas conhecidos

- **`BubbleBox.clampedOrNull` é uma adição além da lista literal da P2**, declarada e não
  contrabandeada: a P2 exige `init` estrito, e o pipeline da spec §7 comprovadamente emite
  `width = 1.0000001f`. Sem uma porta saneada o tipo teria crash garantido. Remover é trivial
  se você preferir.
- **Não dá para injetar gestos de zoom no emulador.** `input tap` duplo é lento demais e
  `sendevent` não chega ao framework (o emulador usa um `virtio_input_multi_touch_N` por
  dedo). Na P3 contornei mudando o **tipo de escala para "tamanho original"** pela UI do
  app, o que dá escala ≠ encaixe e permite pan de um dedo só. A P15, que é sobre double
  tap, vai precisar de verificação manual sua.
- **Nunca editar `shared_prefs` por fora.** Tentei isso na P3 para mudar o tipo de escala e
  zerei as preferências do app, perdendo a pasta de armazenamento e caindo no onboarding.
  Use a UI de configurações do próprio app.
- **Todo gesto sobre a página já tem dono.** Toque simples é navegação, double-tap é o zoom
  da `SubsamplingScaleImageView`, e long-press é interceptado pelo `GestureDetectorWithLongTap`
  do `Pager`, que abre o menu de ações de página. Por isso o gatilho temporário da P4 é um
  broadcast: `adb shell am broadcast -a eu.kanade.tachiyomi.BUBBLE_ZOOM_DEBUG_FOCUS`.
  A P6, que pode mexer em navegação, é quem deve resolver isso de verdade.
- **Double tap e pinça não são verificáveis por automação.** Dois `adb shell input tap`
  nunca chegam perto o bastante no tempo. Toda fase que dependa de gesto de zoom
  (P15 em especial) precisa de verificação manual do usuário.
- **`resetBubbleZoom` restaura o estado capturado, não um "padrão" recalculado.** O Mihon
  enquadra página nova com `setScaleAndCenter(scale, canto)`, e o canto padrão resolve para
  RIGHT (leitura RTL + `zoomStart` Auto, `PagerConfig:112-115`). Voltar ao centro geométrico
  deixaria o leitor onde a página nunca esteve, sempre que ela transborda no `minScale`.
- **O overlay só anexa quando o host é `PagerPageHolder`.** `MangaCoverDialog` também
  constrói um `ReaderPageImageView`, então sem essa guarda as caixas de depuração eram
  desenhadas sobre as capas de mangá — defeito introduzido na P3 e pego na revisão da P4.
- **Duas contradições dentro do prompt da P5, resolvidas contra a letra dele.** (a)
  `tryMovePrevious` do índice 0 devolve **true** e vai para -1, não false: a P6 exige que a
  volta seja o reverso exato, e obedecer ao prompt apagaria a parada da página inteira,
  deixando 3 paradas na volta contra 4 na ida. (b) "reseta o zoom" é resetar o **índice**;
  traduzir isso em câmera é da apresentação, que é o que permite à P16 trocar o card sem
  tocar no controller.
- **`BubblePageKey` compara por identidade, não por valor.** Uma chave `(chapterId, pageIndex)`
  colidiria entre uma página dividida e a própria metade dela, porque `InsertPage(parent)` é
  construída com `parent.index` literal.
- **A escala alvo usa `min(por largura, por altura)`, não só a largura como o prompt da P4
  dizia.** Medido em paisagem: só pela largura, o balão 0 ocuparia 137% da altura da tela.
  O `min()` o deixa em 43%.
- SystemUI deu ANR uma vez logo após o boot frio, sob carga. Não é do Mihon; some sozinho.
- O adb do Nox (`D:\Program Files\Nox\bin\adb.exe`) pode disputar a porta 5037.
  Sintoma: `adb devices` vazio. Conserto: `adb kill-server`.
- O emulador reclama que não acha o adb se a janela dele for aberta por um terminal
  iniciado antes de `ANDROID_HOME` existir. É inofensivo — só afeta a barra lateral dele.
- Com `highQualityRenderer` (preferência base) ligado, `ReadingMode.toViewer` devolve um
  `WebGpuViewer`, que não tem o Bubble Zoom: a preferência fica ligada e sem efeito, sem
  aviso. Visto na P9 ao ler `ReadingMode.kt`; não afeta a configuração do emulador.

## Defeitos da P16 achados pela revisão e corrigidos

- **`doOnEnd` dispara também no `cancel()`.** Quando `show()` cancelava uma saída em curso, o
  callback daquela saída rodava na hora e reciclava o bitmap que o `show()` acabara de
  instalar, porque fechava sobre o **campo** e não sobre o bitmap que estava dispensando.
  Sintoma: toque consumido, nada desenhado, balão pulado. O mesmo valia para o `outgoing`,
  apagando a imagem do cross-fade. Corrigido comparando por identidade. Verificado no aparelho
  com `animator_duration_scale 10`.
- **Card e controller podiam desalinhar.** Se o card se dispensasse sozinho (pinça, duplo
  toque), o controller continuava achando que havia foco, e o toque no terço do meio era gasto
  desfocando algo invisível em vez de abrir o menu. Agora o holder reseta o controller junto, e
  o presenter esquece a câmera salva para não desfazer o gesto do leitor.
- **O reenquadramento do `landscapeZoom` reporta `ORIGIN_ANIM`**, igual às nossas animações, e
  a guarda da P6 (`scale != minScale`) não o pega porque o caminho do card não mexe na escala.
  Um card erguido agora veta esse reenquadramento.

## O que a P16 apurou, e que contraria o que eu supunha

**O card é tão nítido quanto o zoom de câmera.** Eu supunha que no encaixe só houvesse tiles
de baixa resolução. Falso: com `setMinimumTileDpi(180)` e a tela a 420 dpi, o
`fullImageSampleSize` dá 1 e a página inteira já está na memória em resolução plena (vale para
páginas até ~1620 px de largura em viewport de 1080). O card é ampliação de 1,416× de pixels
reais, e como o nível de tile é função da escala, câmera e card leem dos mesmos tiles.

**"Dispensar com toque fora do card" é inconstruível.** O `dispatchTouchEvent` do `Pager`
alimenta o detector de gestos incondicionalmente, então um toque consumido pelo card também
navegaria: um dedo, duas ações. Decisão do usuário: o **terço do meio** dispensa enquanto há
balão em foco, e o toque seguinte abre o menu. Uma linha no `PagerViewer`.

**O card recusa e a câmera assume** quando: não há image view, a imagem não está pronta, o
balão está parcialmente fora da tela, ou a magnificação sai da banda 1,1–2,6×. Acima da banda a
câmera mostra genuinamente mais, porque ao mudar a escala ela faz a biblioteca decodificar
tiles mais finos — coisa que uma captura congelada não pede. Verificado em paisagem: a página
passou de 720 para 2205 px de largura, ou seja a câmera assumiu.

**O card é uma captura congelada**, consequência direta da decisão de não escurecer: a página
fica parada e visível, então se o leitor arrastar ou der pinça por baixo, o card vira mentira
e é dispensado em vez de mostrar pixels velhos.

## Lição da revisão da P5

Os dois achados que sobreviveram eram **lacunas de teste, não defeitos de código** — e um
deles era uma asserção morta que eu mesmo escrevi (`assertEquals(first, ...let { _ -> first })`
compara `first` consigo mesmo). Com ela, a metade positiva do contrato de igualdade de
`BubblePageKey` nunca foi exercitada. O outro: nada afirmava **qual** balão o `focusFor`
devolvia, só se era nulo.

Fechei os dois e **provei por mutação**: com o lookup invertido, exatamente os dois testes
novos falham e nenhum outro. Vale repetir esse teste de mutação nas fases seguintes — uma
suíte verde não diz nada sobre o que ela deixaria passar.

## O que a revisão da P6 achou, e o que ficou por fazer

A revisão encontrou **um defeito crítico que eu tinha deixado passar**: `activatePage` era
chamado com os três balões para toda página, então o controller consumia os três primeiros
toques mesmo em páginas onde nada podia ser desenhado — imagem animada, download falho, ou
ainda carregando. Em página animada era permanente. É a regressão de leitura normal que a
regra 2 proíbe, e a causa foi eu ter posto a guarda na camada de apresentação.

Corrigido movendo a guarda para a navegação: `activatePage` agora só torna a página o sujeito
(estado `Detecting`, que recusa todo movimento), e o holder só oferece os balões quando tem
uma image view pronta. Verificado com um GIF animado de verdade: um toque vira a página.

**Ao corrigir, quebrei pior e só descobri porque verifiquei no emulador.** O `onBubblesReady`
não checava o estado, e o coletor do holder o chama a cada emissão — então todo avanço era
desfeito no mesmo instante e a página nunca virava. O conserto foi exigir `Detecting` no
controller. Lição: verificação no aparelho não é formalidade de fim de fase.

Também corrigido: o reenquadramento de 500 ms do `landscapeZoom`, que não reavaliava nada e
arrastava a câmera para fora do balão. Agora ele desiste se a escala já não for a de encaixe.

### Não corrigido, para a P7 decidir

- **O `PageEntry` vem do `forward` do Mihon, que erra em fronteira de capítulo sem transição.**
  O `forward` compara números de página locais ao capítulo, então ir da página 20 do capítulo 1
  para a página 1 do capítulo 2 dá `false` — entrada BACKWARD numa página que deveria abrir
  normal. Só acontece com "sempre mostrar transição de capítulo" desligado e o capítulo vizinho
  já carregado. Precisa de decisão de desenho, não de remendo.
- **Sem re-aplicação ao reanexar um holder.** O coletor é cancelado no detach e não reinicia.

## Comportamentos observados na P6 que não são defeito, mas você deve saber

- **Toques mais rápidos que ~300 ms são absorvidos** pelo detector de duplo-toque do Android
  e não navegam. Medido: 12 toques em 259 ms produzem zero viradas; a 400 ms produzem
  exatamente 2 viradas para 8 toques. Não corrompe estado. Acredito ser pré-existente do
  Mihon (a `SubsamplingScaleImageView` sempre teve duplo-toque), **mas não verifiquei contra
  um build vanilla**.
- **Swipe com a página ampliada arrasta a imagem em vez de virar a página.** Comportamento do
  Mihon para imagem deslocável. O estado não corrompe e o toque seguinte retoma a caminhada.
- **Girar a tela devolve a página ao estado não-focado.** A página é preservada e não há
  crash, mas você perde o balão em que estava. Causa: girar recria a activity, logo um
  `BubbleZoomController` novo. Candidato a resolver na P11/P13 ou a simplesmente aceitar.

## Avisos da P5 que a P6 consumiu

- `config.navigateToPan` tem padrão **true**: o `moveRight` do Mihon faz pan em vez de virar
  a página enquanto a imagem puder ser arrastada, o que é sempre verdade logo após uma
  caminhada de balões. A P6 precisa ler `controller.isFocused` **antes** de chamar o
  controller e pular a checagem de pan nesse evento.
- `landscapeZoom` tem padrão **true** e posta um reenquadramento com 500 ms de atraso, que
  arrastaria a câmera para fora de um balão em páginas largas.
- Numa transição de capítulo não existe `ReaderPage`. Sem uma chamada a `deactivate()`, a
  página que saiu continua sendo o sujeito.

---

# E1 — recorte no formato exato do balão

Não é fase do plano. É uma emenda de escopo pedida depois da P16 já commitada e vista no
aparelho, e está registrada como **Emenda E1** em `docs/BUBBLE_ZOOM_SPEC.md`. A linha
"segmentação precisa do contorno do balão" estava explicitamente **fora** do escopo, então a
decisão foi do usuário, com os três caminhos e os custos de cada um postos na mesa. O
escolhido: traçar o contorno dos próprios pixels, sem modelo.

## O que existe agora

`BubbleMask.kt`, Kotlin puro e sem nenhum import Android. Recebe os pixels do card já
reduzidos e devolve uma silhueta, ou `null`. O caminho é: mediana de uma mancha central para
achar o tom da superfície, preenchimento de quatro vizinhos a partir dali, recuperação do que
ficou cercado, e crescimento para levar o contorno junto.

Três decisões que valem estar escritas:

- **A mediana, não o pixel do meio.** O centro exato de um balão cai numa letra tão facilmente
  quanto no papel, e uma letra mandaria o preenchimento traçar a letra. Metade de uma mancha
  não pode ser texto.
- **Reduzir para 192px antes de traçar é parte do algoritmo, não otimização.** Reduzir é um
  filtro passa-baixa: é o que faz retícula virar cinza chapado em vez de milhares de buracos.
  De quebra, a máscara volta a ser desenhada filtrada, então a borda chega anti-serrilhada de
  graça, e o traço inteiro custa uma fração de milissegundo.
- **Recusa em vez de chutar.** Se o preenchimento alcança a borda, ele escapou por uma falha
  no contorno e está segurando a página; se cobre menos de 10% achou uma letra; mais de 92%,
  achou a página. Nos três casos o card volta ao retângulo da P16. Balão aberto, legenda sem
  borda e balão do mesmo tom da arte caem todos aqui — degradação, não falha.

O contorno é **só apresentação**. Vive e morre dentro do card, nunca é persistido, e
`BubbleDetection` continua carregando só uma `BubbleBox` retangular normalizada. P8, P9 e P13
seguem exatamente como estavam planejadas.

## O que a revisão adversarial achou

Quatro lentes, e cada achado passou por três agentes tentando refutá-lo. Dez achados, seis
sobreviveram a 2 de 3 refutações. Todos corrigidos.

- **A troca entre balões não animava nada, e isso vinha da P16.** `animateTo` construía o
  animador a partir do campo `progress`, que já vale exatamente 1 depois de qualquer entrada
  completa — então o segundo balão em diante animava de 1 para 1. O card que saía era
  desenhado com opacidade `1 - progress`, isto é, zero, e o que entrava não tinha o pequeno
  crescimento. Corte seco, não transição. Era justamente o ponto que o commit da P16 listou
  como "não verificado, precisa de uma pessoa".
- **Dois `dismiss()` seguidos matavam a esmaecida, e eles acontecem em todo arrasto.** Um pan
  com o balão focado faz o holder chamar `onPageMoved()` e depois resetar o controller, cujo
  novo estado chega ao presenter como um segundo dismiss. O segundo cancelava o primeiro, e
  cancelar roda a callback do primeiro na hora — reciclando os bitmaps um quadro depois do
  início de uma esmaecida de 180 ms, com eles ainda opacos na tela. Agora um campo `exiting`
  lembra de quem já está saindo e o segundo dismiss não faz nada.
- **O guarda de escape não tinha cobertura nenhuma.** Os dois testes que pareciam cobri-lo são
  na verdade barrados pelo teto de área antes de chegarem lá — apagar `if (escaped) return
  null` deixava a suíte inteira verde. O teste novo usa página de tinta, balão de papel e um
  corredor vazando pela esquerda: escapa, mas cobre um terço do card, dentro da faixa legal.
  **Provado por mutação:** com o guarda apagado, só ele fica vermelho.
- **A duração era escalada duas vezes.** `animateTo` multiplicava por `animatorDurationScale`
  e entregava a um `ValueAnimator`, que multiplica pelo mesmo ajuste internamente. Quadrático
  num ajuste que o leitor pode mudar. Também vinha da P16.
- **O crescimento era dimensionado pelo lado curto da máscara**, com a justificativa de que o
  contorno é fração constante do balão. É falso: a mesma caneta desenha balão grande e balão
  pequeno, então o contorno é fração constante da **página**. E como o card é sempre
  enquadrado para ocupar a mesma parte da tela, um balão pequeno é ampliado mais e seu traço
  cai mais **grosso** na máscara. Agora quem calcula é `BubbleCardGeometry.outlineGrowPasses`,
  a partir da largura da página e do tamanho da caixa na origem. Provado por mutação também.

Os outros quatro achados foram refutados: 2 ou 3 dos verificadores mostraram que o código já
tratava o caso, ou que a alegação se apoiava em leitura errada de uma API do Android.

## Verificação

| | |
|---|---|
| Testes JVM | 100, zero falhas — 14 novos (86 antes da E1) |
| Simulação nas 5 páginas de teste reais | **18 de 18 balões traçados**, zero recusas |
| Cobertura do card pela silhueta | 0,46–0,58 (0,74 nos balões de canto, onde o padding trunca) |
| Cantos do card | vazios, como tem de ser — não é retângulo |
| Formas devolvidas | retângulo arredondado, elipse, retângulo arredondado — as do gerador |

A simulação roda o algoritmo linha a linha em Python sobre os PNGs de verdade, então não é
teste de fixture sintética: são os pixels que o aparelho vai ver.

### No emulador

Medido pela pegada do card, isto é, os pixels que mudam entre o estado sem card e com card.

| | pegada | linha do topo | cantos (oitavos) |
|---|---|---|---|
| P16, retângulo | 653x671 | 613 de 653 | 0,97 / 0,97 / 0,33 / 0,24 |
| E1, balão 0 (ret. arredondado) | 541x491 | 388 de 541 | 0,27 / 0,28 / 0,24 / 0,25 |
| E1, balão 1 (elipse) | 548x471 | 81 de 548 | **0,00 / 0,00 / 0,00 / 0,00** |

A pegada caiu de 653x671, que é a caixa acolchoada inteira, para 541x491, que é o balão em si
(368x336 na origem, ampliado 1,46). O acolchoamento sumiu porque virou transparente, e na
elipse os quatro cantos dão exatamente zero. Os 613 de 653 na linha do topo do build anterior
eram a borda retangular atravessando o card inteiro.

**Recusa verificada no aparelho.** Na página 4 os três boxes de debug caem em papel em branco,
o preenchimento escapa, e o card volta a **653x671 com cantos 0,97** — o retângulo da P16, no
mesmo build e na mesma sessão em que a página 1 dá forma.

**Transição pega no meio do voo** com `animator_duration_scale 10`: a elipse de um balão
saindo e o retângulo arredondado do outro entrando, os dois desenhados ao mesmo tempo, cada um
na sua forma. Antes da correção isso era impossível de ver, porque o card que saía tinha
opacidade zero desde o primeiro quadro. A saída também esmaece, e o retorno ao estado inicial
bate **exatamente 0 pixel de diferença**.

Sem crash em `logcat -b crash` em toda a sessão.

### Custo, medido e não estimado

25 amostras quentes, todas traçando forma, instrumentando o aparelho e revertendo depois:

| | mediana | máximo |
|---|---|---|
| `BubbleMask.extract` | 2,3 ms | 4,0 ms |
| recorte no alpha (`DST_IN`) | 2,8 ms | — |
| captura da página (custo da P16) | ~8 ms | — |
| **total por balão** | **14,9 ms** | 17,0 ms |

Reduzir e ler os pixels custa 0,7 ms somados; o trabalho está todo no preenchimento.

Duas coisas que só a medição mostrou, e que a minha aritmética tinha errado:

- **As três primeiras chamadas da vida do processo custam 62, 30 e 16 ms**, contra 2,3 ms
  quentes. É aquecimento do JIT, não o algoritmo. Na prática o primeiro balão de uma sessão
  aparece uns 80 ms depois do toque; do segundo em diante, não.
- **14,9 ms encosta no orçamento de 16,7 ms de um quadro**, e o `gfxinfo` durante a caminhada
  deu 4 quadros atrasados em 40, pior caso 31 ms. Disso, ~8 ms são a captura da P16 e ~5 ms
  são o que a E1 acrescentou. Num emulador x86_64; não medi em aparelho real, então não
  afirmo que lá seja melhor, embora seja o provável.

Não otimizei. Tirar traçado e recorte da thread principal salvaria ~5 ms dos 15, mas exigiria
passar bitmaps entre threads — exatamente a classe de mudança que gerou os três defeitos de
ciclo de vida de bitmap da P16. Se o solavanco for visível para você, aí vale pagar esse
preço; a medida está aqui para essa decisão ser informada.

## O que só uma pessoa responde

- A silhueta lê como balão destacado ou como recorte mal feito sobre arte ocupada?
- A auréola de duas tonalidades separa o card da página, agora que não há retângulo? Sem
  scrim, ela é a única coisa que separa.
- Com a transição consertada, balões consecutivos se transformam um no outro sem piscar?

---

# P7 — a preferência

Duas preferências de leitor, `bubbleZoom` e `bubbleZoomDebug`, ambas falsas por padrão, e a
regra 2 do projeto virando verdade: com a primeira desligada o app é Mihon vanilla.

## O desenho

Uma classe, `BubbleZoomSession`, é tudo que o leitor enxerga da feature. Ela possui o
controller, as duas preferências e a decisão de quando uma página pode ser percorrida; as
classes upstream só chamam através dela. É isso que faz "desligado é vanilla" ser verificável
lendo **um** arquivo em vez de auditando três.

O `BubbleZoomController` ficou como estava — puro, sem preferência e sem escopo — porque a
máquina de estados dele tem 33 testes que teriam de ganhar dependência de Android para dizer
qualquer coisa. E a sessão recebe `Preference<Boolean>`, não `ReaderPreferences`, com uma
função-fábrica para o chamador: é o que permite exercitar o toggle inteiro na JVM.

O `BubbleZoomPagePresenter` passou a ser dono de **todas** as views da feature, o card e a
overlay de debug. O holder nunca mais precisa lembrar de tirá-las.

## Cinco armadilhas que a investigação pegou antes de eu escrever código

- **A overlay de debug não conseguia alternar ao vivo onde estava.**
  `prepareNonAnimatedImageView` retorna cedo quando a view já existe, então o `attach` só
  acontecia uma vez na vida da página. Mover a posse para o presenter foi o que resolveu.
- **`ReaderPageImageView` não pode ler preferências.** `MangaCoverDialog` e `WebtoonAdapter`
  também a constroem. Tirar a overlay de lá resolveu isso de vez — e aposentou o guarda
  `parent !is PagerPageHolder` que a P4 teve de acrescentar por causa disso.
- **`presenter.release()` não era o botão de desligar.** Limpava o card e nunca chamava
  `restoreCamera`, então desligar a preferência com uma página enquadrada pela câmera da P4
  deixava ela ampliada num balão que ninguém mais via.
- **Ligar sozinho deixava o leitor morto até a próxima virada de página.** `enable()` só chega
  a `Idle`, e o leitor anuncia uma página exatamente uma vez, quando a seleciona.
- **A metade `scale != minScale` da guarda do `landscapeZoom` era divergência não-portão.**
  Agora é uma pergunta só, `isBubbleZoomPresenting()`, que responde `false` com a feature
  desligada — devolvendo o comportamento original do Mihon, que a P6 tinha apertado para todos.

## Três defeitos que os juízes acharam no meu código já escrito

- **Um listener de SharedPreferences por página, com a feature desligada.** `presentation` era
  um `Flow` frio e `Preference.changes()` é um `callbackFlow`, então cada `PagerPageHolder`
  registrava o seu próprio, criado e destruído a cada virada de página, até três vivos. Agora é
  um `stateIn` no escopo do viewer: uma assinatura para o leitor inteiro, que é o que o
  `PagerConfig` faz com as doze preferências dele.
- **Ligar durante a decodificação deixava a página inerte.** O `apply` retorna cedo sem imagem
  pronta, e o `onImageLoaded` só invalidava as caixas. Agora existe `applyBubbleZoom()`,
  chamado dos dois lados, porque eles chegam em qualquer ordem.
- **O interruptor não existia dentro do leitor**, e sem ele o requisito da fase é
  inexercitável: se o único lugar é Ajustes do app, você tem de *sair* do leitor para alternar.
  `landscapeZoom`, `navigateToPan` e `cropBorders` estão nos dois lugares; agora Bubble Zoom
  também.

## Onde o interruptor aparece

Em dois lugares, como os análogos dele:

- **Ajustes → Leitor → grupo "Pager"**, no fim, com subtítulo, e o de debug acinzentado
  enquanto o principal estiver desligado.
- **Engrenagem dentro do leitor → Reading mode**, logo depois de "Pan wide images", e o de
  debug só existe na lista quando o principal está marcado — o mesmo aninhamento do
  `dualPageSplit`.

## Verificado no aparelho

| | OFF (padrão) | ON |
|---|---|---|
| Views da feature (`dumpsys activity top`) | **nenhuma** | `BubbleZoomOverlayView` |
| Pixels magenta | 0 | 0 com debug off, 1477 com debug on |
| Páginas em 8 toques | **6** | 3 |

O `dumpsys` é a prova estrutural: desligado, nenhuma view da feature **existe**. Não é "existe
e não desenha".

O toggle ao vivo funciona nos dois sentidos, pelo engrenagem de dentro do leitor. Ligar pegou
na página já aberta, sem virar página. A overlay de debug ligou ao vivo — o caso que não
funcionava antes. Desligar removeu todas as views e devolveu um toque por página. Sem crash.

A caminhada, medida toque a toque: sem foco → balão de cima → do meio → de baixo → vira a
página. E entrar numa página **indo para trás** aterrissa no último balão, que é o
comportamento desenhado na P5 — isso explicou um desencontro que eu quase reportei como bug.

## Um caminho que não dá para testar à mão

Desligar a preferência **com um card levantado** é inalcançável pela UI: para chegar nos
ajustes você toca no terço do meio, que primeiro solta o foco. É a mesma restrição da P16 que
tornou "tocar fora para dispensar" impossível — e significa que nenhum leitor real alcança esse
estado. O `switchOff` restaura a câmera mesmo assim, por precaução.

## A revisão adversarial desta fase não é confiável

Ela reportou zero achados confirmados, e esse número **não vale**. Oito dos 34 agentes morreram
no limite de sessão, e eram exatamente os verificadores dos achados sobre os meus testes:
"The suite cannot see the deletion of…" (os três refutadores), "Three of the ten tests pin no
session behaviour…" (os três), "'off by default' is in a test name but…", e um sobre o caminho
de desligar. Achado sem nenhum voto caía em "dismissed" no meu script de agregação, então ele
contou como descartado o que nunca foi julgado.

Isso foi resolvido por conta própria, com sweep de mutação sobre o `BubbleZoomSession` — cinco
mutações, uma por comportamento que os testes alegam prender.

### O sweep de mutação, e duas lições sobre o próprio arnês

**As duas primeiras execuções do sweep não invocaram o Gradle uma única vez.** A causa é
Windows: `subprocess.run(shell=True)` abre o **cmd.exe**, não o bash, e lá `export` não é
comando e `./gradlew` não é programa. A primeira versão do script então imprimiu "as cinco
mutações sobreviveram", que teria sido um relatório inteiramente falso.

O que salvou foi a aritmética, não a ferramenta: uma das mutações remove literalmente a guarda
que um dos testes afirma existir, então "sobreviveu" era impossível. **Regra para esta base:
resultado bom demais ou ruim demais merece checagem do arnês antes de virar conclusão.** O
script agora distingue "a suíte rodou e não pegou" de "a rodada nem chegou nos testes", que era
o que ele confundia.

Com o arnês consertado, o resultado real:

| mutação | morta por |
|---|---|
| semente síncrona removida | *(nada — ver abaixo)* |
| ligar não reativa a página já aberta | `switching on mid-read gives the walk the page…` |
| `offerBubbles` não recusa enquanto desligado | `off means the bubbles are never even asked for` |
| caixas de debug não forçadas a off com a feature | `the debug boxes are forced off whenever…` |
| desligar não desabilita o controller | duas, incluindo `switching off mid-walk…` |

**A semente síncrona sobreviveu, e o buraco era real.** Os testes usavam
`UnconfinedTestDispatcher`, que roda o `launchIn` na hora da construção — apagando exatamente a
janela de um tick que a semente existe para fechar. No aparelho o `MainScope()` usa
`Dispatchers.Main`, que despacha no tick seguinte, e o leitor seleciona a primeira página dentro
dele. O teste novo usa `StandardTestDispatcher`, que não roda nada até o escalonador avançar,
e mata a mutação.

Dois testes foram acrescentados nessa conta. O outro é sobre o nome: `off by default…` montava
a sessão com `enabled = false` na mão, então nunca tocava no padrão de verdade e teria passado
igual com um padrão `true`. Agora há um que constrói `ReaderPreferences(InMemoryPreferenceStore())`
e afirma que as duas nascem falsas, e o antigo perdeu a parte do nome que não cumpria.

Doze testes na sessão, 112 no pacote, **zero buracos de cobertura no sweep**.

## Resíduo conhecido, dito em vez de escondido

- **`PagerPageHolder` está em +62 linhas sobre o upstream**, quatro vezes a regra de ~15 do
  CLAUDE.md. A P7 encolheu o arquivo (era +71) e ele continua bem acima. `ReaderPageImageView`
  caiu de +34 para +28.
- **Um coletor suspenso por page holder, mesmo desligado.** É o preço do requisito de alternar
  sem reiniciar, e é também o único caminho que desmonta as views quando a preferência cai no
  meio da leitura. Sendo um StateFlow que não emite mais enquanto desligado, o custo é um
  coroutine suspenso e nada mais.
- **`onImageMoved` continua sendo encaminhado de `onCenterChanged`**, que o upstream deixava
  vazio. Com a feature desligada ele chama `invalidateBoxes()` sobre um campo nulo e retorna.
  Comportamento idêntico; não é byte a byte.

---

# P8 — a abstração do detector

`BubbleDetector` com `modelVersion` e `suspend detect(image)`, um `FakeBubbleDetector` que
responde as três caixas conhecidas depois de fingir que pensa, e o pipeline inteiro ligado.
Nada acima da interface sabe de onde vêm as caixas — nem que existe um detector falso.

## Três decisões que a investigação impôs

**`page.stream` é a fonte errada, e errar aqui é silencioso.** O `process()` do holder pode
girar uma página larga noventa graus ou cortar uma dupla ao meio antes de exibir, e o
`InsertPage` copia o stream do pai literalmente — então a metade direita de uma dupla,
perguntada pelos próprios bytes, devolve a dupla inteira. Um detector alimentado dali retorna
frações perfeitamente válidas do retângulo errado, e nada estoura. Os bytes certos são o
`okio.Buffer` em voo dentro do `setImage()`, e só ali: o okio invalida o `peek()` assim que a
view drena a fonte.

**O `BubbleDetectionImage` não carrega `Bitmap`.** Decodificar no chamador é a segunda cópia em
resolução plena que as regras proíbem, e o chamador não sabe em que resolução o modelo quer
trabalhar. Carregando *como ler os bytes*, o detector decodifica uma vez, no tamanho dele.
De quebra mantém `Bitmap` fora do tipo, o que deixa tudo acima dele testável na JVM — este
projeto não tem Robolectric nem `returnDefaultValues`, então um campo `Bitmap` bastaria para
derrubar qualquer teste que encostasse no tipo.

**As dimensões vêm dos bytes, não da view.** Elas concordam, mas a view ainda não está pronta
no instante em que os bytes existem, e esperar significaria reconciliar dois fatos que chegam
em lugares e momentos diferentes. `inJustDecodeBounds` lê um cabeçalho e não aloca pixel nenhum.

## Duas instruções do brief que não foram seguidas

**"O controller agora pergunta ao detector" não é implementável.** O `activatePage` é chamado
de `PagerViewer.onReaderPageSelected`, antes de qualquer decodificação — a imagem não existe
nesse instante, e aparece depois, em outro objeto. Somado a isso, dar escopo e `Bitmap` ao
controller quebra 33 testes JVM e torna a classe não-testável. A orquestração ficou na sessão,
que já tem escopo. A máquina de estados continua indo `Idle → Detecting → Ready/Error` como o
brief manda; o que mudou foi quem faz a chamada.

**"Escopo atado ao page holder" vazaria a activity.** O `PagerPageHolder.scope` é um
`MainScope()` que **nunca** é cancelado — `onDetachedFromWindow` cancela dois jobs pelo nome.
Um terceiro job lançado ali sobreviveria ao holder, ao pager, ao viewer e à activity, segurando
um `FrameLayout` → `context` → `ReaderActivity`. O `PagerViewer.scope` é cancelado em
`destroy()`, e é lá que a detecção roda. O cancelamento no recycle continua existindo: o holder
chama `forget(page)` no `onDetachedFromWindow`.

## Por que a resposta precisa poder esperar

Os bytes e a seleção da página chegam de lugares diferentes, em qualquer ordem. Uma página que
decodificou enquanto o leitor ainda estava duas páginas atrás tem a resposta pronta antes de o
controller ter ouvido falar dela — e o `onBubblesReady` só responde um `Detecting` pendente.
Sem um lugar para esperar, essa resposta seria descartada e a página ficaria inerte enquanto o
leitor estivesse nela. Daí o mapa por página na sessão, limitado pelo pager (`offscreenPageLimit`
é 1, três páginas vivas) e não por política. **Não é o cache da P11 e não pode virar um aqui.**

## Limitação conhecida, e é uma regressão

Ligar a preferência no meio da leitura **não traz bolhas para a página que já está na tela** —
só para a próxima que chegar. Guardar os bytes de três páginas para uma feature desligada é
exatamente o custo que "desligado é vanilla" proíbe, então a captura não acontece enquanto
desligado, e quando liga não há o que detectar para a página corrente. A P7 verificou o
comportamento contrário no aparelho, quando as caixas eram uma constante disponível na hora.
A P11, que guarda resultados e não bytes, é onde isso se resolve de verdade.

## Verificação

| | |
|---|---|
| Testes JVM | 122, zero falhas — 22 novos (16 na sessão, 6 no detector falso) |
| ktlint, compilação, APK | passam |

O sweep de mutação matou quatro das seis na primeira passada. **Uma das duas sobreviventes era
mutação quebrada, não buraco:** a P3 inseriu uma linha morta antes do comentário e deixou o
`deliver(page)` real intacto, medindo nada. Refeita com âncora correta, morre pelo teste certo.
Segunda vez nesta sessão que o arnês de mutação mente, em disfarce diferente — vale reler a
regra escrita na P7.

A sobrevivente real é a P6, reportar uma detecção cancelada como falha. É invisível pela
superfície pública: o `forget` remove a entrada antes, e de fora `Detecting` e `Error` dão o
mesmo `IGNORED`. Engolir `CancellationException` é erro de concorrência estruturada, não de
comportamento, e está registrado em vez de tapado com um teste que só existiria para matar a
mutação.

Dois avisos que o detector falso **não** prova, e que P9 vai encontrar: um `delay` não segura
thread nenhuma, então um `withContext(Dispatchers.Default)` faltando fica invisível até um
modelo real virar quadro perdido; e `delay` é ponto de cancelamento, enquanto inferência nativa
não é — "cancelado no recycle" aqui significa "a resposta é descartada", que é a metade que
sobrevive na P9.

---

# P9 — o detector real

`Kiuyha/Manga-Bubble-YOLO`, variante nano (YOLO26n, 6 MB), rodando em ONNX Runtime 1.30.0 na
CPU, a 640 de entrada. A primeira vez que as caixas saem da página em vez de uma constante.

## A escolha do modelo, e a licença que não é o que a ficha diz

Três candidatos na spec. O `kitsumed` é GPL-3.0 e devolve máscaras que não precisamos. O
`ogkalu` é Apache-2.0 mas só tem `.pt` — exigiria exportar — e pesa 52 MB. O `Kiuyha` é
Apache-2.0 na ficha, tem ONNX pronto com NMS **dentro do grafo** (uma etapa a menos para
errar), saída documentada `(1, 300, 6)`, treino em Manga109 + Mangadex, mAP@50 0,947.

**Mas os metadados dentro do `.onnx` dizem `AGPL-3.0 (ultralytics.com/license)`.** É o carimbo
do framework de treino: a Ultralytics sustenta que pesos treinados com o pacote deles caem sob
AGPL salvo licença Enterprise; o autor do repositório diz Apache-2.0. Ambiguidade conhecida e
não resolvida. Para uso pessoal, irrelevante. Para distribuir o APK, reabre o aviso da spec — e
as duas alternativas "Apache" têm o mesmo carimbo, porque também foram treinadas com
ultralytics. A saída limpa seria treinar sem o pacote deles ou baixar o modelo fora do APK,
ambas fora do V1. **Decisão do usuário, registrada aqui para não ser esquecida.**

O runtime custa: **39 MB de `libonnxruntime.so` por ABI** (o split x86_64 ficou em 119,7 MB).
Número para a P18 pesar quantização int8 ou LiteRT.

## O layout do tensor foi confirmado, não lido de ficha

O README não documenta o que as seis colunas significam — exatamente a lacuna que o plano
manda não preencher com palpite. Confirmação empírica em três passos, no PC, antes de uma
linha de Kotlin: (1) o script do próprio plano, que mostrou `dynamic: True` (640 roda),
`end2end: True`, `names: {0: 'text'}`; (2) o modelo rodando nas cinco páginas sintéticas com
`tools/verify-detector.py`, decodificando como `[x1, y1, x2, y2, score, cls]` em pixels da
entrada letterboxada — **18 de 18 detecções dentro do balão conhecido, desvio de centro
≤ 0,004**; (3) o decodificador Kotlin preso, por teste JVM, às linhas que o Python produziu.

## O que o modelo devolve não é o balão

`tools/diagnose-detector.py` separou duas hipóteses que o IoU sozinho confunde: geometria
errada (caixas deslocadas) ou conteúdo diferente (caixas menores, centradas). **18/18 contidas
na caixa-verdade, zero parcialmente fora.** O modelo enquadra a **região de texto**, não o
contorno do balão: 31%–69% da área, IoU 0,3–0,7 contra a caixa do balão. É propriedade do
modelo, não bug, e vira decisão de padding para a P10 — o traçado da E1 cresce do centro até
o contorno, então uma caixa mais justa funciona se a área acolchoada alcançar o traço.

640 contra 1280: mesmas detecções, um quinto do custo, e 1280 acrescentou falsos positivos
numa página. O 640 da spec fica.

## As três coisas que a integração real ensinou

**As 300 linhas nunca são zero.** A primeira versão do decodificador guardava "tudo acima de
zero", assumindo que as vagas sem detecção eram zeros. São candidatos a 0,0004–0,07. O
aparelho percorreu **211 "balões"** numa página com oito. O teste não pegou porque **o fixture
tinha sido filtrado em 0,25 antes de a suposição ser escrita** — um fixture filtrado pela mesma
suposição que o código faz não testa a suposição. Agora o fixture são as 300 linhas cruas
(`app/src/test/resources/bubblezoom/`), e `CONFIDENCE_THRESHOLD = 0.25` recupera exatamente o
ground truth nas cinco páginas. **Aviso para a P10:** o 0,35 da spec cortaria um balão real de
0,28 na página 03.

**A overlay de debug desenhava as três constantes da P3.** Ninguém a religou ao controller
desde então, e foi invisível enquanto as constantes *eram* as detecções. Agora desenha a
lista do controller para a página.

**O sweep de mutação e o bug do aparelho eram o mesmo buraco por dois lados.** A única
mutação sobrevivente foi "linhas com score zero não são mais descartadas" — sobreviveu porque
o fixture tinha zeros literais que o `clampedOrNull` derrubava sozinho. Com o fixture real, a
mutação equivalente morre por três testes. Zero buracos.

## Duas instruções do brief não seguidas, por fato de código

O escopo de detecção é o do viewer (P8 já tinha estabelecido por quê: o do holder nunca é
cancelado). E o tempo de inferência vai para o logcat, não para `BubblePageResult` — nada
constrói esse tipo ainda; a P11 é onde resultados passam a ser guardados.

## A revisão adversarial morreu, e o que ela deixou eu julguei à mão

35 de 37 agentes caíram no limite de sessão. Desta vez o agregador separa "não julgado" de
"descartado", e os onze não-julgados foram lidos contra o código. Seis eram reais:

- **`close()` correndo com inferência em voo vazava a sessão ORT.** O `if (closed)` rodava
  dentro do `withLock`, antes do `unlock`; o `tryLock()` do `close()` falhava; os dois lados
  desistiam. Agora quem encontra `closed` com a trava livre libera — de fora do lock.
- **`Error` não é `Exception`.** `UnsatisfiedLinkError` da lib nativa e `OutOfMemoryError`
  escapavam das duas redes e matavam o processo, só com a feature ligada. Ambas pegam
  `Throwable` agora (rethrow de `CancellationException`).
- **Páginas animadas voltaram a engolir toques.** A P8 removeu o `offerBubblesWhenReady` da
  P7 e com ele a garantia da P6. Um GIF recebia detecções reais, entrava em `Ready`, e os
  toques eram consumidos sem nada desenhado. Restaurado como `pageReady(page)`: a resposta só
  chega ao controller depois que o holder confirma uma imagem zoomável. E GIF não é detectado.
- **Página de 1200px decodificava em resolução plena** — o caso comum de scanlation. Agora
  `FULL_DECODE_BELOW = 1.5`: acima de 960px de lado longo, meia resolução com leve upscale.
- **Modelo ausente não era "exatamente como desligado".** `Error` não é `Disabled`: o card
  ficava anexado e o veto ao reenquadramento ativo. Agora um `Error` da própria página desmonta
  as views, e `BubbleDetector.available` faz a sessão parar de copiar bytes para um detector
  que já sabe que não funciona.
- `SessionOptions` nunca fechada; mapa de detecções mutado da thread IO. Ambos corrigidos.

## Verificado no aparelho

Uma captura por toque ao longo do capítulo, com `tools/measure-walk.py` identificando cada
página por correlação com os PNGs sintéticos (contar toques falhou duas vezes: a página de
transição existe, e entrar para trás foca o último balão).

| página | existem | detectados | casados | FP | FN | dentro do balão | IoU |
|---|---|---|---|---|---|---|---|
| 01 | 3 | 3 | 3 | 0 | 0 | 3/3 | 0,63 · 0,59 · 0,44 |
| 02 | 8 | 8 | 8 | 0 | 0 | 8/8 | 0,71–0,73 ×4 · 0,32–0,35 ×4 |
| 03 | 3 | 3 | 3 | 0 | 0 | 3/3 | 0,43 · 0,42 · 0,38 |
| 04 | 0 | 0 | 0 | 0 | 0 | — | — |
| 05 | 4 | 4 | 4 | 0 | 0 | 4/4 | 0,44–0,45 |

**18/18, zero FP, zero FN, 18/18 dentro do balão.** Inferência **150–206 ms** por página no
emulador x86_64 (logcat), mais a decodificação. Zero crashes. A ferramenta de medição foi
validada antes numa captura da P7 com resposta conhecida (IoU 0,96–0,97, a perda é o traço).
Uma ressalva de medição: o card erguido cobre a caixa magenta do balão focado, então a página
tem de estar sem foco para medir — a primeira passagem reportou FN=1 na página 01 por isso.

## Verificado numa HQ real, e o que ela mudou no veredito

O usuário pôs *Batman (2025-) #1* (DC; 31 páginas WEBP de 1988×3057, cinco spreads de
3975×3057; fonte online) na biblioteca do emulador. É uma HQ ocidental, colorida, com
letreiramento em inglês — fora da distribuição de um modelo treinado em manga, portanto um
teste mais duro que as três páginas Apache-2.0 do próprio autor, e é o material que o usuário
escolheu. Nenhuma página entrou no projeto: as capturas ficam em `tools/shots/p9-real-*`
(gitignored), e o cache do capítulo foi copiado para o scratchpad da sessão só para a medição
offline, e apagado depois.

### Como se anda por uma HQ com o Bubble Zoom ligado

Uma captura por página, em fit e sem foco, com a overlay de debug ligada. Para a frente,
`DPAD_RIGHT` percorre os balões e só vira a página depois do último (313 pressões para 31
páginas). Para trás, `ctrl+DPAD_LEFT` vira a página direto — `moveToPrevious()` sem foco é
"página anterior" — e entra focando o último balão; um toque na zona central dispensa.
`ctrl+DPAD_RIGHT` **não** vira: `moveToNext()` passa por `moveRight()`, onde está o gancho, e
foca o balão 1 (25 pressões na página 4 até eu perceber). Duas falhas do arnês antes de haver
resultado: o detector de "página nova" comparava o contador por diferença média e não via
2→3, 5→6, 7→8 (glifos da mesma largura) — oito páginas puladas na primeira passada; e a
página 1 estava com zoom quando cheguei, então os primeiros swipes só faziam pan. A leitura do
que o modelo devolve foi feita **à mão, captura por captura**, porque uma HQ real não tem
ground truth.

### O que o detector encontrou, página a página

Contei "regiões de texto que um leitor quereria percorrer": balões e legendas. Não contei
onomatopeias, logos, placas nem indicia. Capa, anúncios, créditos e entrevista (1, 2, 9,
29–31) ficam de fora: nelas o modelo caixa logos, créditos e texto de anúncio, que é texto e
não diálogo, como se espera de um detector de texto. A coluna "1280" é offline (abaixo).

| pág. | existem | achou (aparelho, 640) | nano @ 1280 | nota |
|---|---|---|---|---|
| 3 | 11 | 3 | 10 |  |
| 4 | 6 | 6 | 6 |  |
| 5 | 3 | 3 | 3 |  |
| 6 | 11 | 8 | 8 | perde o balão grande 'HE BEGAN ECDYSIS' |
| 7 | 16 | 9 | 12 | perde pilhas de balões encostados |
| 8 | 10 | 3 | 1 | spread 3975 px |
| 10 | 18 | 10 | 20 |  |
| 11 | 11 | 11 | 12 |  |
| 12 | 29 | 9 | 32 | enxame de 18 balões minúsculos: 1 achado |
| 13 | 9 | 5 | 9 |  |
| 14 | 11 | 8 | 10 |  |
| 15 | 10 | 5 | 9 | 0 de 5 balões; só legendas; FP: placa |
| 16 | 10 | 6 | 13 | FP: onomatopeia |
| 17 | 8 | 6 | 9 |  |
| 18 | 5 | 1 | 1 |  |
| 19 | 11 | 9 | 11 |  |
| 20 | 15 | 9 | 14 |  |
| 21 | 12 | 6 | 10 |  |
| 22 | 15 | 13 | 14 |  |
| 23 | 6 | 2 | 7 | FP: uma nuvem |
| 24 | 2 | 0 | — |  |
| 25 | 12 | 5 | 10 | legendas em caixa preta |
| 26 | 18 | 16 | 17 |  |
| 27 | 12 | 2 | 8 | 0 de 7 balões |
| 28 | 9 | 0 | — | spread de legendas: nada |
| **25 páginas** | **280** | **155** | **253** | **55 % de recall no aparelho; 3 FP → precisão ≈ 98 %** |

Nas sintéticas foi 18/18. Numa HQ real, à resolução e ao limiar de hoje, **o detector perde
quase metade do que existe**. O que ele acha é certo (as caixas ficam no texto; três falsos
positivos em 25 páginas); o que ele perde: pilhas de balões encostados, balões pequenos,
spreads — e também balões grandes e comuns (p6 "HE BEGAN ECDYSIS…", p7 "HE'S BEEN EXPOSED…").
Duas páginas com zero balão achado (15 e 27), e o spread de legendas (28) sem nada.

### A alavanca não é o modelo, é a resolução de entrada

Os dois exports aceitam entrada dinâmica (`['batch', 3, 'height', 'width']`), o que a ficha
não diz. Rodei offline, com a mesma letterbox e o mesmo decodificador, sobre as 46 páginas do
cache (as contagens offline ficam a ±1–3 das do aparelho, que decodifica reduzido):

| config | detecções ≥ 0,25 nas 25 páginas de história | ms/página neste PC |
|---|---|---|
| nano @ 640 (o que está no aparelho) | 170 | 73 |
| nano @ 1280 | 253 | 264 |
| small @ 640 | 149 | 150 |
| small @ 1280 | 244 | 638 |

O modelo maior a 640 é **pior** que o nano a 640 na maioria das páginas; a 1280 os dois
empatam. A diferença está no tamanho do texto no tensor: uma página de 1988 px a 640 reduz
por 0,32, e um balão de 150 px vira 48 px com letras de 4 px; a 1280 dobra. Nas quatro
páginas que renderizei com as duas escalas, as caixas extras a 1280 são balões reais: p15
passa de 0/5 balões para 5/5; p12 (o enxame) de 9 para 32, todos certos; p3 de 2/10 para
8/10; p27 de 2/7 para 4/7 (a página mais difícil, letreiramento leve). Falsos positivos que a
1280 acrescenta nessas quatro: um letreiro de néon (p3) e um quadrinho pequeno (p15). A 1280
também **perde** caixas que a 640 tinha (p8: 4→1, p4: 8→6, p26: 19→17); a união das duas
escalas seria o melhor, e é decisão de desenho, não desta fase. Custo estimado no emulador:
~600 ms/página (3,6× os 170 ms medidos), ainda dentro de "chega tarde" para página
pré-carregada. `INPUT_SIZE = 640` é uma constante; mudá-la é uma linha e um teste, e fica
para a P10 decidir junto com o limiar — a 1280, balões reais caem em 0,10–0,25 (p27, p3),
então limiar e escala não se decidem separados.

### O card da E1 nunca aparece numa HQ real

Em todas as capturas focadas o que se vê é a câmera da P4 (a página ampliada), com a caixa de
debug por cima; nas sintéticas o card erguido a cobria. Não é defeito: `show()` é recusado por
`magnificationAllows`, que aceita 1,1–2,6× sobre a escala de fit, porque uma captura congelada
dos tiles carregados para o fit não tem detalhe para mais que isso. As caixas do modelo são de
texto, e sobre uma página de 1988 px vista em 1080 px pedem, na mediana, **4,2×** (297 caixas
≥ 0,25; 15 % dentro da faixa). Crescendo as caixas até o balão (×1,6), 40 % entram; ×2, 54 %;
×3, 65 %, mas aí 28 % caem abaixo do mínimo. Ou seja: a E1 que você pediu não é visível numa
HQ real com a geometria de hoje, e a câmera da P4 é o caminho que serve — e funciona: foca,
avança, vira a página. Decisão sua: subir `CARD_MAX_MAGNIFICATION` (card borrado), capturar
de tiles mais finos (memória e latência), ou aceitar a câmera como o caminho das páginas
reais e deixar o card para balões grandes.

### Também visto

- 46 páginas distintas detectadas em 127–304 ms (logcat), zero crashes, ao longo de ~400
  pressões de tecla e ~390 capturas.
- Um spread (3975 px) é uma página só, como a regra manda; a 640 é quase cego (p8: 3/10,
  p28: 0/9).
- Com `highQualityRenderer` ligado o leitor usa `WebGpuViewer`, onde o Bubble Zoom não existe
  — a preferência fica silenciosamente sem efeito. Não é o caso desta configuração; está em
  Problemas conhecidos.

Testes JVM: 137 no pacote, 26 novos (11 no decodificador, o resto na sessão e no detector
falso). O `FakeBubbleDetector` continua selecionável por preferência, para A/B no mesmo lugar.

## E2 — o que o Play Books faz, medido antes de escrever código

O usuário abriu o Google Play Books no emulador com uma amostra de HQ e pediu que o Bubble
Zoom funcionasse como lá. A observação está resumida na emenda E2 da spec; aqui fica o que a
spec não carrega — como foi medido e o que deu errado no caminho.

**Injeção de gestos.** `input tap` duas vezes não faz duplo-toque: cada chamada é um
processo Java de 300–500 ms. Dois processos em paralelo (`input tap & input tap`) dão
intervalo incontrolável — ora duplo-toque, ora dois toques simples, ora dois dedos — e
produziram resultados contraditórios (o mesmo gesto avançou de balão numa captura e deu
zoom de página na seguinte). `sendevent` por evento também é lento demais no emulador (o
"duplo-toque" virou dois toques simples na zona esquerda: voltou de página e foi ao balão
anterior). O que funcionou: `adb root` (a imagem é userdebug), e cada fase do toque como um
arquivo de `struct input_event` (24 bytes, protocolo B, sem `BTN_TOUCH`) escrito com um
único `cat > /dev/input/event2` — down, 50 ms, up, 100 ms, down, 50 ms, up. Validado contra
o zoom nativo do Play Books em arte vazia (liga e desliga). Cuidado herdado: `adb shell`
junta os argumentos e o shell do aparelho re-parseia, então o comando vai como **uma**
string, senão `sh -c cat … > dev` vira um `cat` lendo stdin para sempre. E o menu do Play
Books, quando aberto, engole tudo — três lotes foram descartados por isso até o driver
normalizar o estado (menu, zoom, página por correlação) antes de cada teste.

**Medição dos cards** (diferença de pixels entre captura com e sem card; balão medido pela
caixa de pixels claros):

| balão | balão em tela | card | ampliação | card / largura da tela |
|---|---|---|---|---|
| WHAT THE HELL (p2) | 152×90 | 387×263 | 2,55× | 0,36 |
| HERE, INSIDE A NIGHT (legenda, p1) | 258×103 | 676×291 | 2,62× | 0,63 |
| A NIGHT WITHOUT MORNING (p1) | 175×54 | 473×168 | 2,70× | 0,44 |
| HERE, IN THIS PLACE (p1) | 199×85 | 661×256 | 3,32× | 0,61 |

A altura do card inclui a sombra, por isso a razão vertical sai maior. O card ficou centrado
no balão em dois casos (desvio ≤ 10 px) e deslocado em dois (95 e 150 px para baixo e para a
direita), sem regra que eu tenha conseguido ler de quatro amostras. Nada disso é constante
única de 60 % de viewport: é ampliação fixa com teto.

**Sequências capturadas** (todas em `tools/shots/pb-*.png` e `_pb-*.png`, gitignored):
dicas 1–4 do app; duplo-toque em balão, em arte vazia (com e sem card), sobre o card, noutro
balão; swipe para cima e para baixo com card, na fronteira do primeiro e do último balão;
toque na borda com e sem card; toque no centro com e sem card; tecla de volume; swipe
horizontal com card. A animação de entrada não foi gravada: o emulador caiu antes do lote
(`adb devices` vazio), e eu não o inicio.

**O que muda no veredito da P9.** A observação da HQ real dizia que o card da E1 nunca
aparecia numa página real e que a câmera da P4 era "o caminho que serve". A E2 decide o
contrário: a câmera sai e o card passa a ser construído da imagem nativa, o que remove a
razão da faixa de 2,6×. A tabela de magnificação daquela seção (15 % dentro da faixa) vira
histórico.

## E2 — o que a revisão achou, e o que ela não chegou a olhar

A revisão adversarial bateu no limite de sessão: das quatro lentes, **três responderam**
(toque, ciclo de vida, máquina de estados) e a de **geometria/decodificação não rodou**; os
**onze verificadores morreram**. Os 18 achados foram recuperados do journal e **julgados à
mão contra o código**, como na P9. Cinco eram reais e estão corrigidos, mais um erro meu de
documento.

**Corrigidos:**

1. **Swipe vertical sem card virava a página.** O achado estava certo sobre o fato: um flick
   para baixo chamava `moveToPrevious()` e voltava de página num pager horizontal, onde o
   Mihon não faz nada com arrasto vertical. Eu restringi o swipe ao card erguido; **o usuário
   reverteu depois de ver a medição do Play Books**, que anda nesse gesto, e o swipe sem card
   voltou — com a guarda que o achado também apontou e que ficou: uma página que pode ser
   arrastada mantém o arrasto, e um duplo-toque que virou *quick scale* da biblioteca não é
   lido também como swipe. A divergência em relação ao upstream está declarada na spec.
2. **Duplo-toque sobre o card pulava para o vizinho.** O ponto ia para o espaço da página, e
   o card é ~2,8× o balão: ele cobre os vizinhos, então o toque caía na caixa de outro balão
   e o card trocava em vez de sumir. Agora o retângulo do card decide primeiro.
3. **Um card recusado prendia a caminhada.** Na recusa eu chamava `session.reset()`; o evento
   que selecionou o balão já tinha sido consumido, então o próximo passo selecionava o mesmo
   balão, era recusado de novo, e a página **nunca podia ser virada** por toque, volume ou
   swipe. Agora o foco fica onde está: uma parada silenciosa, e o passo seguinte a ultrapassa.
4. **Decodificação cancelada vazava o bitmap.** `withContext` descarta o resultado ao ser
   cancelado, então o bitmap decodificado nunca era reciclado; e numa caminhada rápida as
   decodificações se acumulavam num pool ilimitado. Agora o resultado sai por um holder que o
   `finally` sempre vê, e um mutex serializa as decodificações desta página.
5. **Corrida `detect`/`forget` retinha os bytes da página.** O holder registra os bytes na
   thread de decodificação e a inserção no mapa era feita dentro da corrotina; um holder que
   se desprendesse nessa janela deixava os bytes — agora megabytes, porque o card lê deles —
   presos até o leitor fechar. O registro passou a ser síncrono e atômico. Além disso,
   esquecer a página que **é** o sujeito agora a desativa: quando o leitor muda uma
   preferência que reconstrói todas as páginas (crop borders, rotação), as caixas antigas
   descreviam bytes que estão sendo trocados.

**Erro meu de documento, corrigido:** a spec dizia *"Sem card, swipe vertical não faz nada"*
sobre o Play Books. As capturas `pb-G2`/`pb-G3` mostram o contrário — sem card, o swipe para
baixo vira para a página anterior e já entra no **último balão** dela (a legenda "THIS PRISON
OF GODS" aparece ampliada e com sombra). A medição está corrigida, e o desvio em relação ao
Play Books agora está declarado como escolha, não como imitação.

**Julgados como não-defeito:** o toque simples continuar chegando ao pager com card erguido
(é o que mantém as zonas funcionando, e é intencional); o primeiro toque de um duplo-toque
ainda confirmar um *single tap* na image view 300 ms depois (`performClick` → `onViewClicked`,
que no pager não tem consumidor); a caminhada terminar com *pan* numa página que o **usuário**
ampliou antes de erguer o card (é o comportamento do upstream com a feature desligada, logo
está certo); e `isHoldingThePage` permanecer verdadeiro durante os 180 ms do fade de saída.

**Não revisado:** geometria e decodificação — `cardScale`, `regionOf`, `sampleSize`, a
verificação de quadro do `BubbleCardDecoder`, e se a máscara traçada do *replay* estica
corretamente sobre o bitmap nítido de outro tamanho. A lente morreu antes de rodar e eu não a
substituí por leitura própria. É a parte da E2 com menos cobertura crítica.

**Testes:** 163 no pacote (eram 137 antes da E2), incluindo 12 para o hit test e três para as
corridas corrigidas. Um buraco conhecido: a recusa do card e o gesto em si não têm teste JVM —
`BubbleZoomOverlayView` e `BubbleZoomGestures` dependem de `Bitmap` e `MotionEvent`, e este
projeto não tem Robolectric.

## E2 verificada no aparelho, e um defeito da E1 que só agora deu para ver

Edição #2 da mesma HQ, 33 páginas, uma página com nove detecções. Toques injetados por evdev
(o pacote precisa de `ABS_MT_TOUCH_MAJOR` e `ABS_MT_PRESSURE`: sem eles o InputReader deste
emulador não os promove a toque, e nada acontece — uma hora foi perdida nisso depois de o
emulador reiniciar). Tudo medido em pixels, não olhado:

| o que | resultado |
|---|---|
| duplo-toque num balão | card ergue, **3,13× × 3,75×** a caixa de texto |
| **a página se move?** | diferença fora do card: **média 0,00, máximo 0** — nem um pixel |
| swipe para cima | card vai para outro balão |
| swipe para baixo | volta ao card anterior, **diferença 0,00** (exato) |
| duplo-toque no card | card some; página intocada (**0,00 / 0**) |
| swipe vertical sem card | **0,00** — upstream preservado |
| duplo-toque em arte vazia, sem card | página amplia (**202,06**), segundo duplo-toque volta exato |
| decodificação nativa | 262×188 e 225×148 px, **68–71 ms**, sample 1 |
| detalhe do card | Laplaciano **1,32×** o de um upscale bicúbico da tela — a decodificação nativa entrega detalhe que os tiles não tinham |
| duplo-toque noutro balão | pula para ele; página intocada (**0,00 / 0**) |
| toque na zona de avanço com card | anda para o próximo balão |
| andar além do último balão | **vira a página no 8º toque** — sem armadilha |

**O defeito:** o card sai como o **retângulo arredondado**, não no formato do balão. A máscara
da E1 é recusada em toda a página. A razão, medida e não suposta: o detector caixa o **texto**,
e a caixa acolchoada por `FOCUS_PADDING` (0,20 do menor lado) **não contém o balão** — a elipse
é cortada pela captura e a cauda fica aberta, então o preenchimento escapa pela borda inferior
(27 pixels de borda, em `tools/shots/_e2leak.png` dá para ver o verde saindo pela cauda). O
guard de escape recusa, corretamente: ele não distingue um vazamento de um contorno com falha.

Varrendo o padding sobre os nove balões desta página: **0,2 → 0/9**, 0,4 → 1/9, 0,5 → 2/9,
0,6 → 3/9, **0,7–1,0 → 4/9**, 1,3 → 2/9 (aí a máscara fica pequena demais e cai no outro
guard). Mais padding ajuda e satura em menos da metade.

Isto **não é regressão da E2**: é a primeira vez que o card aparece numa HQ real — na P9 ele
era recusado em toda página real pela faixa de magnificação, então a máscara da E1 nunca tinha
sido exercida fora das páginas sintéticas, onde a caixa detectada era quase o balão e o
traçado funcionava. A P9 já tinha previsto em texto: *"o modelo devolve texto e não balão,
então `boxPadding` é a alavanca que faz o traçado da E1 alcançar o contorno"*. Agora está
medido.

**Não corrigido de propósito.** Mudar como a E1 traça é redesenhar uma emenda que o usuário
pediu, e a regra é uma fase por sessão. O caminho que a E2 abriu e que parece certo: traçar a
máscara a partir de uma **região maior que o card**, decodificada em resolução nativa pelo
`BubbleCardDecoder` (que agora existe), e recortar a máscara para o retângulo do card — a
captura passaria a conter o balão inteiro e a cauda, e o contorno estaria nítido em vez de
reduzido a 192 px a partir de tiles borrados. Custa uma segunda decodificação por card.
Decisão do usuário.

Uma linha de log nova diz, por card, se a silhueta foi traçada. No aparelho ela sai
`BubbleZoom mask 192x151 grow=8 -> NONE` em **todos** os cards, que é a confirmação de que a
análise offline acima descreve o app e não uma aproximação dele. Sem essa linha, os dois
cards são duas features diferentes vistas de fora, e foi o que me custou a investigação.

## E3 — o recorte no formato do balão, agora numa HQ real

O usuário mandou corrigir depois de ver a medição. A emenda está na spec; aqui ficam os
números e o que eles custaram.

**A causa era de enquadramento, não de algoritmo.** O traçado rodava dentro do card, e o card
era a caixa do **texto** acolchoada — então o balão não cabia nela: a elipse saía cortada, a
cauda ficava aberta, e o preenchimento escapava pela ponta. Uma replicação minha anterior tinha
dito que o traçado deveria funcionar; ela estava contaminada, porque eu havia recortado da
captura de tela **com a overlay de debug ligada**, e a caixa magenta servia de barreira que o
app não vê. Repetida sobre a página limpa, ela reproduziu a recusa exatamente.

**Varreduras que decidiram as constantes**, nos nove balões de uma página:

| região de busca | traçados |
|---|---|
| 0,2 (o que existia) | 0/9 |
| 0,8 | 4/9 |
| **1,2** | **5/9** |
| 1,6 e 2,2 | 0/9 e 2/9 — aí o piso de área recusa, porque o balão vira fração pequena |

Com o vazamento fino permitido: **6/9**. Uma escada de tolerância (0,18 → 0,12 → 0,08 → 0,05)
foi medida e **não acrescentou nada**, então não entrou.

As duas populações separam limpo, e é daí que saem os números:

| | cobertura da região | borda tocada |
|---|---|---|
| silhuetas certas | 10,1–15,1 % | 0,0–0,6 % |
| preenchimentos que escaparam | 33,3–47,8 % | 20,9–29,4 % |

**No aparelho** (edição #2, página com oito balões): o log diz `traced` em cinco e `NONE` em
três, e olhando os cards são quatro silhuetas inequívocas — incluindo um **balão de grito com
pontas** e duas caudas longas inteiras — contra o retângulo em todos antes. A página continua
sem se mover: diferença fora do card **0,00 / 0** nos oito. Os que caem no retângulo são
honestos: legenda sobre fundo claro, balão cuja parede de fundo tem o mesmo tom, balões
sobrepostos.

**O swipe sem card voltou**, por decisão do usuário depois de ver que o Play Books anda nesse
gesto: verificado no aparelho, um swipe para cima sem card ergue o primeiro balão. A guarda que
a revisão pediu ficou — página que pode ser arrastada mantém o arrasto, e *quick scale* não é
lido como swipe.

Testes: 173 no pacote (eram 163), com o `BubbleMaskTest` reescrito em torno das guardas novas —
a cauda fina é mantida, o vazamento largo é recusado, a silhueta que não segura seu texto é
recusada, e uma legenda de borda justa é aceita, que o piso antigo recusaria.

## E4 — todos os balões, e o que isso custou

O usuário pediu o recorte de **todos** os balões. Antes de trocar de método, quatro ideias foram
medidas nos nove balões de uma página e **descartadas com número**:

| ideia | resultado |
|---|---|
| escada de tolerância 0,18 → 0,03 | nenhum ganho (5/9 → 5/9) |
| parada por gradiente de borda | **piora**: abaixo de 0,30 quebra os que funcionavam |
| selagem morfológica de frestas | nenhum ganho (6/9 no melhor caso) |
| região de busca maior | já era a E3 |

A varredura por balão explica por quê: em "YOU MORON" **não existe janela de tolerância** — a
0,14 o preenchimento inunda a parede, a 0,10 não sai do lugar. Não é fresta no contorno, é
sobreposição de tons. Traçado por pixels não resolve isso.

**O modelo de segmentação resolve.** Medido antes de entrar no app, nas mesmas páginas: todos os
balões, sobrepostos separados, balão de grito com pontas, e balões que o detector de texto
perdia. Só a legenda de localização "THE SLABS" fica de fora, o que é correto para um modelo de
"speech bubble".

**Verificado no aparelho**, página de oito balões, cada um com duplo-toque e medição em pixels:

| | antes (E3) | depois (E4) |
|---|---|---|
| recortados no formato | 4 de 8 | **8 de 8** |
| cobertura média da caixa do card | — | 64 % (um retângulo daria 100 %) |
| cantos livres | — | 3–4 de 4 em todos |
| página se move | 0,00 / 0 | **0,00 / 0** |
| balões achados por página | 8–12 | **11–18, todos com forma** |
| inferência | 177 ms | **2,1–2,3 s** |
| APK | 126 MB | **215 MB** |

**Os dois custos são reais e ficam registrados, não escondidos:** a inferência é doze vezes mais
lenta, então numa página nova os balões aparecem depois de uns dois segundos — o contrato de
"chega tarde" cobre isso e a página nunca espera, mas é perceptível; e o APK quase dobrou. Se
algum dos dois incomodar, as saídas conhecidas são entrada de 512 em vez de 640, ou a
quantização int8 (28 MB), que **neste PC mediu mais lenta** (454 ms contra 331) e mudou as
contagens — em ARM pode inverter, e só se decide medindo no aparelho.

**Refinamento da máscara.** A grade nativa tem passo 4, então um balão chega com ~50 células e o
card a estica ~8×, o que aparecia como faixa mole de arte em volta. Interpolando os logits e
cortando depois, a faixa mole caiu de **36,4 % para 30,3 %** do card e a borda passou a ler como
contorno em vez de borrão. Comparação em `tools/shots/_e5compare.png`.

**O que saiu:** `YoloEndToEndDecoder`, sua fixture de 300 linhas e o `bubble_detector.onnx` de
6 MB. O `Letterbox` ficou — os dois lados ainda precisam concordar sobre ele — e seus três testes
também. `ONNX_MODEL_VERSION` foi a 2, porque as caixas agora descrevem o balão e não o texto.

Testes: 176 no pacote, 11 novos no decodificador de segmentação — um preso às linhas reais do
modelo (63 âncoras → 43 acima do piso → **8 balões** depois do NMS, igual ao Python), um que
falha se alguém voltar a ler o tensor como adjacente em vez de canal-major, e dois para o
refinamento.

## Prefetch — a detecção chega antes do leitor (parte da P11)

Pedido do usuário depois da E4: *"detecte os balões da página enquanto estou na anterior, assim
não tem delay"*. O modelo de segmentação leva ~2,2 s por página, e isso aparecia.

**Uma página já era detectada adiante, por acidente:** o pager mantém um holder de cada lado,
cada holder decodifica ao nascer, e decodificar é o que dispara a detecção. Mas essa dianteira
é só o tempo que o leitor passa na página anterior.

**Agora as três páginas seguintes à próxima são detectadas a partir dos bytes da própria
página**, sem esperar um holder que não existe. Três decisões:

- **Só quando os bytes em disco são os bytes na tela** — `process()` do holder é a identidade
  quando *dual page split* e *rotate to fit* estão desligados. Com qualquer um ligado, o
  prefetch devolve nada e volta à dianteira de uma página. A alternativa seria reimplementar
  `process()` aqui, e uma segunda cópia divergindo da primeira é como uma detecção acaba
  descrevendo o retângulo errado sem nada avisar. Um `InsertPage` é recusado sempre: seu stream
  é o do pai, e devolveria a página dupla inteira.
- **Enfileiradas depois do que os holders pediram**, que é a ordem que importa: a página
  prestes a aparecer foi enfileirada quando seu holder nasceu, então nada distante a ultrapassa.
- **Esquecidas por posição, não por idade** (ver abaixo).

**Medido no relógio do app**, não em capturas de tela:

| pausa na página | balões ao chegar |
|---|---|
| 6 s | já estavam lá |
| 3 s | já estavam lá |
| 1 s | já estavam lá |
| 0 s, virando o mais rápido possível | o modelo não dá conta |

Dentro do capítulo, com um segundo ou mais por página, **não há mais espera**. Com zero, a fila
enche mais rápido do que esvazia — aritmética do modelo de 2,2 s, não do prefetch.

### Dois erros meus nesta parte, e o que cada um ensinou

**O instrumento mediu a si mesmo.** Reportei "1,2 a 2,3 s de espera por página" e depois "o
prefetch não melhorou nada". Os dois números vieram de um laço que tira capturas de tela — e uma
captura leva **~1,0 s** neste emulador. Tudo que eu media como 1,2 s era *"os balões já estavam
lá na primeira olhada"*. O piso do instrumento nunca tinha sido estabelecido. A medição que
vale saiu de duas linhas de log do próprio app e é precisa em milissegundos.

**Apresentei uma hipótese como diagnóstico.** A primeira eviction descartava a entrada mais
antiga quando o mapa crescia — e a mais antiga é a página enfileirada há mais tempo, ou seja a
**mais próxima**, a que o leitor vai alcançar. Era um defeito real e está corrigido, com um
teste chamado *"a página prestes a ser alcançada nunca é a descartada"*. Mas eu disse que era
*a* causa do atraso, e corrigi-la não moveu a medição: não era. O prefetch já funcionava, e o
que faltava era um instrumento honesto.

Duas linhas de log ficam, porque esta feature é invisível quando funciona e invisível quando
não: `BubbleZoom prefetch offered=3 started=1 known=2 unreadable=0` e `BubbleZoom page ready
with N bubbles`. Sem elas, três diagnósticos seguidos foram adivinhação.

Testes: 189 no pacote, 12 novos — seis para a escolha das páginas (transição no meio, página
não carregada, `InsertPage`, fim da lista, settings que reescrevem a página) e seis para a
sessão (busca adiante, página já conhecida, desligado, crop borders, e as duas de eviction).

## P12 — ordem de leitura, sem detectar painéis

**O problema, medido antes de escrever código.** Depois do NMS o decodificador devolve os balões
por **confiança**, e a ordem de leitura nunca existiu. Com o detector antigo quase não aparecia;
com 11–18 balões por página, aparece sempre. Entrando na caminhada e registrando onde cada card
caía, a posição vertical foi `507 → 1101 → 508 → 760 → 508 → 1382`: desce, volta ao topo, desce,
volta — **duas subidas em sete passos**.

**O desvio da spec, declarado.** A P12 pede detecção de painéis. Não foi feita: achar calha na
arte é a mesma classe de heurística que a E1 e a E3 mediram falhar — uma calha do mesmo tom da
arte ao lado não está lá para ser achada — e um segundo modelo depois de um de 109 MB não se
justifica. O layout é inferido **das próprias caixas**, por corte XY recursivo: uma faixa que
nenhum balão cruza separa fileiras; não havendo, a separação vertical separa quadrinhos lado a
lado; não havendo nenhuma, lê-se em bandas.

**O limite, dito no código:** um quadrinho cujos balões se alinham com os do vizinho é, das
caixas, a mesma figura que um quadrinho largo. Esse caso sai na ordem errada. É muito menor que
ordenar por confiança, e é o preço de não ter painéis.

**Verificado no aparelho**, mesma medição que abriu o assunto:

| | antes | depois |
|---|---|---|
| volta para cima na página | 2 de 7 passos | **0 de 9** |
| sequência vertical | 507, 1101, 508, 760, 508, 1382 | 897, 1231, 1249, 1245, 1244, 1288, 1476, 1631 |

Os passos de x 603 → 664 → 722 na mesma altura são uma banda sendo lida atravessada, que é o
comportamento que a correção abaixo produziu.

**Dois defeitos que os testes acharam, e um era meu de teste.** Uma fixture desenhava "dois
quadrinhos lado a lado" com os balões tão afastados na vertical que a figura vira indistinguível
de uma grade 2×2 — e eu cobrava do algoritmo a resposta que só o conhecimento de painéis daria;
a fixture foi corrigida e o porquê ficou escrito nela. O outro era real: no grupo que não dá
para cortar, a ordenação era por `y` puro, e na página medida no aparelho o balão do meio de uma
fileira estava **um milésimo de página** mais alto que o da esquerda e era lido primeiro.
Lettering é posto a olho; agora a leitura é por bandas, com `ORDER_BAND_TOLERANCE`.

Testes: 197 no pacote, 8 novos — fileiras antes do que está dentro delas, quadrinhos lado a lado
nas duas direções, fileira com quadrinhos de alturas diferentes, aglomerado que não corta, a
página real que abriu o assunto, e a propriedade que um corte recursivo quebra em silêncio:
**todo balão volta exatamente uma vez**.

## Próxima fase

P10 — filtro de confiança e `BubbleDetectionConfig`. Chega com cinco fatos, não três: o piso
é 0,25 e o 0,35 da spec corta balão real; o modelo devolve texto e não balão, então
`boxPadding` é a alavanca que faz o traçado da E1 alcançar o contorno — e também a que decide
se o card entra na faixa de magnificação; `minBoxArea`/`maxBoxArea` têm distribuição medida
nas páginas sintéticas; numa HQ real o recall a 640 é 55 % e a 1280 as caixas extras são
balões, então `INPUT_SIZE` e o limiar se decidem juntos; e o card da E1 não aparece em página
real com a geometria de hoje, o que é decisão do usuário antes de ser código.

