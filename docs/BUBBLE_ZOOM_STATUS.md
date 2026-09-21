# Bubble Zoom — Status

> Atualizar ao fim de cada fase. Este arquivo é o que substitui o contexto de conversas
> antigas com o agente. Mantenha curto e honesto.

## Fase atual

P8 concluída. Próxima: **P9 — detector ONNX real.**

Ordem executada até aqui: P0 → P1 → P2 → P3 → P4 → P5 → P6 → **P16** → **E1** → P7 → P8.

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
- [ ] P9  detector ONNX real + pipeline de imagem
- [ ] P10 confidence filtering + BubbleDetectionConfig
- [ ] P11 cache em memória + prefetch
- [ ] P12 detecção de painéis + BubbleOrderResolver + testes
- [ ] P13 cache em disco com modelVersion
- [ ] P14 teclas de volume
- [ ] P15 double tap
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

Nenhum arquivo de código do upstream foi tocado. O baseline é Mihon `5848ffb4c` sem alterações.

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
- Modelo: _a definir em P9._
- modelVersion atual: **-1**, reservado para o `FakeBubbleDetector` (P8). Um modelo real usa
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

## Próxima fase

P9 — detector real em ONNX, e a primeira vez que as caixas vêm da página em vez de uma
constante. Duas coisas já estão prontas para ele: a porta `BubbleBox.clampedOrNull`, que o
detector falso já atravessa de propósito, e a `modelVersion` reservada em -1, para que o cache
da P13 nunca sirva caixa de debug em página real.
