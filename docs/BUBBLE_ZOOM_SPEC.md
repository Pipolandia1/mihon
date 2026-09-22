# Bubble Zoom — Especificação do V1

Documento congelado. Se algo não está aqui, **não é V1**.

---

## 1. Objetivo

> Em uma página de mangá ou quadrinho, o app identifica automaticamente as regiões que
> contêm diálogo e permite percorrê-las em ordem, ampliando cada uma com animação suave.

Inspiração: Bubble Zoom do Google Play Books.

## 2. Princípios

- Processamento 100% local, sem servidor.
- Sem OCR, sem tradução, sem TTS.
- Sem tocar em extensões/fontes do Mihon.
- Mínimo possível de alteração no código upstream (meta: ≥90% da feature no pacote próprio).
- Uma mudança verificável por vez.
- Bubble Zoom desligado ⇒ Mihon idêntico ao original.

## 3. Escopo do V1

**Dentro:**

- Páginas normais do reader, imagens estáticas.
- Modo **pager** apenas (LTR e RTL).
- Bounding boxes retangulares.
- Navegação entre balões integrada à navegação normal (toque + volume).
- Cache em memória e em disco.
- Overlay visual simples.

**Fora (não é bug, é escopo):**

- OCR, tradução, TTS.
- Webtoon / scroll contínuo.
- Segmentação precisa do contorno do balão **por modelo**. Ver a emenda E1 abaixo: o
  contorno passou a existir, mas traçado dos próprios pixels, sem modelo.
- Sincronização online.
- Reconstrução ou reflow de texto.
- Imagens animadas.

### Emenda E1 — recorte no formato do balão

Decidida pelo usuário depois da P16, com a P16 já commitada e vista no aparelho. A P16
entregou o card como retângulo arredondado; o pedido foi que o recorte fosse no formato
exato do balão.

Três caminhos foram postos, e o escolhido foi o terceiro:

1. Modelo de segmentação (`kitsumed/yolov8m_seg-speech-bubble`). É o único exato em
   qualquer balão, mas é **GPL-3.0**: embarcado num APK distribuído, contamina a
   distribuição, exatamente o aviso da seção de modelos. Recusado.
2. Aproximação por elipse. Barato, nunca exato, e errado em balão de grito ou nuvem de
   pensamento. Recusado.
3. **Traçar o contorno dos pixels que o card já capturou.** Um balão é um contorno fechado
   em volta de uma superfície de um tom só, então a forma se lê da própria página: enche a
   partir do meio, para no contorno, recupera o que ficou cercado (a letra, o retículo) e
   cresce o suficiente para levar o traço junto. Sem modelo, sem licença, sem tocar no
   `BubbleDetection`, no contrato do detector nem no cache da P13.

O que isso **não** muda: detecções continuam sendo `BubbleBox` retangular e normalizada. O
contorno é presentation-only, vive e morre dentro do card, e nunca é persistido — as fases
P8, P9 e P13 seguem como estavam.

Onde não dá para traçar — balão aberto, legenda sem borda, balão do mesmo tom da arte em
volta — o card recusa a silhueta e volta ao retângulo da P16. Degradação, não falha.

### Emenda E2 — o card é a única apresentação, e o gesto é o do Play Books

Decidida pelo usuário depois da P9, com a verificação numa HQ real na mão: numa página real
o card nunca aparecia (as caixas de texto pedem 4× na mediana e a faixa de magnificação
parava em 2,6×), e o leitor via a câmera da P4 ampliando a página. O pedido: **nunca dar
zoom na página**; sempre recortar o balão e ampliar só ele; funcionar como o Google Play
Books — duplo-toque num balão ativa o Bubble Zoom naquele balão, swipe vai ao próximo e ao
anterior, duplo-toque de novo desativa.

O Play Books foi observado no emulador em 21/09/2026, numa amostra de HQ, com toques
injetados por evdev para ter timing exato (capturas em `tools/shots/pb-*.png`, gitignored).
O que ele faz, medido e não lembrado:

- **Duplo-toque num balão** → card com a silhueta exata do balão, cauda inclusa, sombra
  projetada, página intocada por baixo (sem escurecer), balão original ainda visível onde o
  card não cobre. Ampliação em quatro cards: **2,55–3,32×** do tamanho em tela; centrado no
  balão nos casos pequenos; os grandes param em ~63 % da largura da tela (uma legenda de
  258 px virou 676 px). Em dois casos o card saiu deslocado do centro (até 150 px), por regra
  que não identifiquei.
- **Duplo-toque em arte vazia** → o zoom nativo da página (2×), como em qualquer leitor. Com
  card erguido, dispensa o card e dá o mesmo zoom.
- **Duplo-toque noutro balão** com card erguido → pula para ele. Sobre o próprio card → nada.
- **Swipe vertical com card**: para cima = próximo, para baixo = anterior. O pedido dizia o
  contrário; é uma constante (`SWIPE_UP_IS_NEXT`). **Sem card o swipe também anda**: partindo
  da página inteira, um swipe para baixo vira para a página anterior e já entra no último
  balão dela (capturas `pb-G2`/`pb-G3`: a legenda "THIS PRISON OF GODS" aparece ampliada e com
  sombra na página 1). Uma frase anterior desta seção dizia que não fazia nada; era leitura
  errada das mesmas capturas. Depois de medido, o usuário decidiu seguir o Play Books também
  aqui.
- **Toque na borda** = próximo/anterior balão, também sem card (entra no primeiro/último);
  **toque no centro** = com card dispensa, sem card abre o menu; **teclas de volume** = bordas
  (dica 3 do próprio app). É o interruptor "Move between bubbles with Bubble Zoom" do Play
  Books ligado, que é o padrão; desligado não foi testado.
- **Fronteiras**: depois do último balão, "próximo" mostra a página seguinte inteira; de
  novo, o primeiro balão dela. Antes do primeiro, "anterior" mostra a página atual inteira;
  de novo, o último balão da anterior. Cada página tem as paradas
  `[inteira, b0 … bN−1]` — exatamente o modelo que a P5/P6 construíram.
- **Swipe horizontal com card** → vira a página, o card some.
- Animação de entrada: não gravada (o emulador caiu antes); fica para a verificação da E2.

O que a E2 muda no que está construído:

1. **A câmera da P4 sai.** `BubbleZoomFocus.kt` e o fallback do presenter são apagados; o
   card é a única apresentação. Se o card não puder ser construído (balão fora da tela,
   captura falhou), nada acontece — não há segundo caminho, e a página não se move.
2. **A fonte de pixels do card é a imagem em resolução nativa.** `BitmapRegionDecoder`
   sobre os bytes que o holder já entrega à sessão para a detecção — retidos por página
   enquanto o holder vive — decodificando só o retângulo do balão, fora da thread principal.
   A captura instantânea dos tiles da view vira placeholder até o nítido chegar. A faixa de
   magnificação (1,1–2,6×) some junto com a razão que a justificava. O tamanho do card passa
   a ser `CARD_MAGNIFICATION` (2,8×, do Play Books) do tamanho em tela, limitado pelas
   frações de viewport que já existem (~60 %).
3. **Duplo-toque num balão ativa o Bubble Zoom nele** — a P15, agora obrigatória e com regra
   fixa: hit test puro sobre a lista Ready, ponto convertido por `viewToSourceCoord`, menor
   caixa que contém o ponto, tolerância `DOUBLE_TAP_HIT_TOLERANCE` porque as caixas são de
   texto e não de balão. Sem balão sob o dedo, ou antes de a detecção terminar, o duplo-toque
   é o zoom nativo do Mihon, intocado. Com card erguido: noutro balão pula; sobre o card ou
   em arte vazia **desativa** e o gesto é consumido (o pedido; o Play Books não desativa
   sobre o card e dá zoom na arte vazia — aqui não há zoom de página nascido do Bubble Zoom).
   O toque sobre o card é decidido pelo **retângulo do card**, não pelo que a página tem por
   baixo: o card é quase três vezes o balão e cobre os vizinhos, então perguntar à página
   pularia para um deles em vez de dispensar.
4. **Swipe vertical**: para cima próximo, para baixo anterior — com card erguido sempre, e
   também na página em repouso cuja imagem não pode ser arrastada verticalmente, onde a
   biblioteca não faz nada com o gesto e ele está livre. É a caminhada do Play Books, decidida
   pelo usuário depois de ver a medição: da página inteira, um swipe para baixo vira para a
   anterior e entra no último balão dela. **É uma divergência do Mihon**, que não faz nada com
   arrasto vertical numa página em encaixe, e é deliberada. Uma página que pode ser arrastada
   mantém o arrasto, e um duplo-toque que virou *quick scale* da biblioteca nunca é lido também
   como swipe. No pager vertical, onde swipe vertical vira página, o card erguido toma o gesto;
   sem card, vira página como sempre.
5. **Toques, teclas de volume, DPAD e fronteiras ficam como a P6 fez**, que é o Play Books
   com o interruptor ligado. O centro dispensa (P16). Nada disso muda.
6. **Enquanto há card erguido a página por baixo não recebe toques de um dedo** (a image
   view não vê o gesto, então não há pan nem zoom de página sob um card congelado); um
   segundo dedo dispensa o card e a pinça é ignorada até o gesto seguinte; o swipe horizontal
   vira a página como sempre.

**Dois desvios do Play Books, ambos deliberados:** duplo-toque sobre o card desativa (no Play
Books não faz nada); duplo-toque em arte vazia com card erguido desativa em vez de ampliar a
página. Os dois vêm do pedido do usuário e da regra de que a página nunca é ampliada por esta
feature. O terceiro desvio que existiu — swipe sem card não fazer nada — foi revertido pelo
usuário depois de ver que o Play Books de fato anda: **o swipe sem card anda**, e com isso a
garantia "com a preferência ligada e sem card, tudo é upstream" vale para todo gesto menos
esse, que está declarado aqui.

O que **não** muda: `BubbleBox` retangular e normalizado; detector, cache e ordem (P10–P13);
a máquina de estados da P5 (Ready com índice −1…N−1, `PageEntry`); preferência desligada =
vanilla. **P14 e P15 são absorvidas pela E2.**

Fora de escopo continua: manter o card ao girar a tela (a activity é recriada); *crop
borders* (com a preferência ligada o quadro da image view não é o do arquivo — o Bubble Zoom
fica desligado nessas páginas); orientação EXIF.

### Emenda E3 — procurar o balão antes de enquadrar o card

Decidida pelo usuário quando a E2 finalmente pôs um card numa HQ real e ele saiu **retângulo**:
a máscara da E1 era recusada em todo balão da página. A causa, medida e não suposta: o detector
caixa o **texto**, então a caixa acolchoada em que o traçado rodava **não contém o balão** — a
elipse é cortada pela própria captura, a cauda fica aberta, e o preenchimento sai pela ponta
aberta. Nove balões de nove.

A E3 inverte a ordem. O traçado passa a rodar numa **região de busca** muito maior que o card
(`MASK_SEARCH_PADDING = 1.2` do menor lado da detecção), e **a silhueta encontrada passa a ser
o enquadramento do card**: o card mostra o balão que o traçado achou, não "caixa de texto mais
padding". A captura de busca é tirada direto na resolução da máscara, algumas centenas de
pixels, porque ela só é traçada e nunca mostrada.

Três guardas mudaram junto, cada uma com o número que a definiu:

- **Escape virou fração.** Uma cauda pode sair da região — é fina, e cortá-la não custa nada
  visível. Um vazamento toma um lado inteiro. Silhuetas corretas tocaram 0–0,6 % do perímetro;
  todo preenchimento que tinha escapado tocou mais de 20 %. `MASK_MAX_LEAK_FRACTION = 0.05`.
- **O piso de área saiu.** Numa região escolhida para ser grande demais, uma legenda de borda
  justa é pequena e perfeitamente real; o piso de 10 % a recusaria.
- **Entrou "a silhueta segura o texto".** É a guarda que diz *esta forma é daquela detecção* em
  vez de apenas *esta forma tem cara de balão*. Medido entre 68 e 81 % nas silhuetas certas — o
  que falta para 100 é o próprio letreiramento, onde o preenchimento para.
  `MASK_MIN_TEXT_COVERAGE = 0.5`, e um teto de 0,35 da região de busca contra o preenchimento
  que virou página (balões reais mediram 10–15 %, vazamentos 33–48 %).

O que **não** muda: o card continua sendo a única apresentação, os pixels continuam vindo da
decodificação nativa, e onde a silhueta não sai o card continua caindo no retângulo arredondado
— agora enquadrado na caixa de texto acolchoada, como antes. Degradação, não falha.

### Emenda E4 — um modelo de segmentação, e o recorte deixa de depender dos pixels

Pedida pelo usuário: *"quero que ele consiga recortar todos os balões"*. O traçado por tom da
E1/E3 chegava a seis de nove numa página real, e **quatro alavancas foram medidas e descartadas**
antes desta decisão: escada de tolerância (nenhum ganho), parada por gradiente (piora), selagem
morfológica de frestas (nenhum ganho), e região de busca maior (já estava na E3). Os casos que
sobravam não eram de afinação — legenda sobre fundo claro, balão cuja parede tem o mesmo tom,
dois balões que se tocam: em pixels não há o que separar.

`kitsumed/yolov8m_seg-speech-bubble` devolve a máscara de cada balão diretamente. Medido antes
de entrar, nas mesmas páginas: segmenta todos os balões, **separa os sobrepostos**, pega o balão
de grito com pontas, e **acha balões que o detector de texto perdia**. Por isso ele **substitui**
o detector em vez de somar — um modelo de segmentação já devolve as caixas.

O que isso custa, medido e não estimado:

| | antes (yolo26n, caixas) | depois (yolov8m-seg) |
|---|---|---|
| modelo | 6 MB | **109 MB** (APK 126 → 215 MB) |
| inferência no emulador | 177 ms | **2,1–2,3 s** |
| balões por página | 8–12, sem forma | **11–18, todos com forma** |
| balões recortados no formato | 4 de 8 | **8 de 8** |

A licença é a mesma situação já registrada: a ficha do repositório diz GPL-3.0 e a metadata do
`.onnx` diz AGPL-3.0, o carimbo da Ultralytics — igual ao detector que a P9 embarcou e que o
usuário aceitou para uso pessoal. Distribuir continua sendo decisão em aberto, para os dois.

Duas coisas que o formato do export impôs. O grafo **não tem NMS**: uma página de oito balões
volta como quarenta e três âncoras acima do piso, então suprimir duplicatas é trabalho do app.
E o tensor de caixas é **canal-major** — a coluna de uma âncora fica `anchors` floats adiante, não
adjacente —, o que faz uma leitura errada devolver lixo que ainda parece caixa; há um teste só
para isso.

A máscara nativa tem passo 4, então um balão chega com cerca de cinquenta células de largura e
o card a estica umas oito vezes, o que aparecia como uma faixa mole de arte em volta. Ela é
refinada interpolando os **logits** do modelo e cortando depois, de modo que a borda cai onde a
confiança cruza a metade e não numa linha da grade: faixa mole medida caiu de 36,4 % para
30,4 % do card.

O traçado da E1/E3 **fica**: o detector falso não tem formas, e uma detecção sem forma continua
caindo no traçado por pixels e, depois dele, no retângulo.

## 4. Definição de sucesso

1. Página carrega normalmente e **imediatamente**.
2. Detector encontra regiões de diálogo em background.
3. Regiões são ordenadas corretamente (LTR e RTL).
4. Usuário avança e retrocede entre elas.
5. Cada região é ampliada com animação.
6. Ao acabar os balões, a navegação normal de página continua.
7. Com a preferência desligada, o app é indistinguível do Mihon original.

---

## 5. Arquitetura

```
PagerViewer ──── moveToNext / moveToPrevious
     │
     ▼
BubbleZoomController ──── BubbleZoomState
     │           │
     │           └──── BubbleZoomCache (memória → disco)
     │
     ├──── BubbleDetector (interface)
     │          ├── FakeBubbleDetector
     │          └── OnnxBubbleDetector / LiteRtBubbleDetector
     │
     └──── BubbleOrderResolver (painéis → balões, LTR/RTL)
                │
                ▼
     PagerPageHolder → ReaderPageImageView
                │
                ├── pageView (subsampling image view)  ← nunca animada (E2)
                └── BubbleZoomOverlayView              ← desenho / debug
```

### Pacote

```
app/src/main/java/eu/kanade/tachiyomi/ui/reader/bubblezoom/
├── BubbleBox.kt                 // x,y,w,h normalizados 0–1
├── BubbleDetection.kt           // box + confidence + type
├── BubblePageResult.kt          // lista ordenada + metadados + modelVersion
├── BubbleType.kt                // TEXT, PANEL, ...
├── BubbleDetectionConfig.kt     // thresholds, padding, tamanho de input
├── BubbleZoomConfig.kt          // durações, zoom alvo, limites
├── detector/
│   ├── BubbleDetector.kt        // interface
│   ├── FakeBubbleDetector.kt
│   └── OnnxBubbleDetector.kt
├── BubbleOrderResolver.kt       // puro, sem Android, testável
├── BubbleZoomController.kt
├── BubbleZoomState.kt
├── BubbleZoomCache.kt
├── BubbleZoomOverlayView.kt
└── BubbleZoomDebugOverlay.kt
```

### Pontos de integração upstream (confirmar na fase P1)

Prováveis, **não assumir sem verificar**:

| Arquivo | Alteração esperada |
|---|---|
| `ReaderPageImageView` | expor conversão de coordenadas + `focusOnBubble()`; hospedar o overlay |
| `PagerPageHolder` | avisar o controller quando a imagem estiver pronta; cancelar no recycle |
| `PagerViewer` | desviar `moveToNext` / `moveToPrevious` pelo controller |
| `ReaderActivity` | teclas de volume |
| `ReaderPreferences` + tela de settings | a preferência `bubbleZoom` (e `bubbleZoomDebug`) |

Cada um desses deve receber **poucas linhas**, delegando para o pacote `bubblezoom`.

### Estado do controller

```
Disabled  → preferência off, ou página incompatível
Idle      → página ativa, detecção ainda não pedida
Detecting → job em background rodando
Ready     → lista ordenada disponível, índice atual = -1 (nenhum balão) … N-1
Error     → falhou; comporta-se como Disabled, sem crash
```

Operações: `activatePage(page)`, `tryMoveNext(): Boolean`, `tryMovePrevious(): Boolean`,
`selectBubble(index)`, `reset()`, `disable()`.

`tryMoveNext()` retorna `false` quando não há mais balões — e aí o Mihon faz a
navegação normal de página. **Toda a navegação passa por esse único ponto.**

---

## 6. Sistema de coordenadas

Regra: **o que é guardado é normalizado, o que é desenhado é pixel.**

```
detector (input 640×640)
   → box em pixels do input
   → divide pelo tamanho do input → 0–1
   → guarda/cacheia assim
   → na hora de focar: multiplica pelo tamanho da imagem ORIGINAL
   → converte imagem→view com o método da própria image view
   → anima
```

Nunca reimplementar a conversão imagem→tela na mão. A image view do reader já expõe
isso; use os métodos dela.

Zoom alvo: a região (com padding) deve ocupar ~**60% da largura útil** da tela,
limitado por `minScale` e `maxScale` da view. Animação de **180–220 ms**.

> **Emenda E2:** a view nunca é animada — só o card. O alvo é `CARD_MAGNIFICATION`
> (2,8×) do tamanho em tela, limitado às frações de ~60 % acima.

---

## 7. Detector

### Contrato

```kotlin
interface BubbleDetector {
    val modelVersion: Int
    suspend fun detect(image: BubbleDetectionImage): List<BubbleDetection>
}
```

O reader não conhece nada além disso.

### Runtime — decisão recomendada

Comece com **ONNX Runtime Mobile** (`com.microsoft.onnxruntime:onnxruntime-android`),
não com LiteRT. Motivo prático: os modelos públicos de detecção de balão já existem em
`.onnx`; ir de LiteRT exige um passo de conversão que pode falhar em operadores do YOLO
e te trava logo no começo. A interface `BubbleDetector` permite trocar depois sem tocar
em nada do reader — que é exatamente o ponto dela.

### Modelos candidatos (verificar licença antes de distribuir)

| Modelo | Formato | Observação |
|---|---|---|
| `kitsumed/yolov8m_seg-speech-bubble` | `.pt` + `.onnx` dinâmico | segmentação de balões; licença **GPL-3.0** |
| `ogkalu/comic-speech-bubble-detector-yolov8m` | YOLOv8 | detecção de balões em quadrinhos |
| `Kiuyha/Manga-Bubble-YOLO` | YOLO | alternativa |

**Aviso de licença, leve a sério:** pesos GPL-3.0/AGPL-3.0 embarcados em um APK
distribuído contaminam a distribuição. Para uso pessoal no seu aparelho, sem problema.
Se algum dia quiser publicar o fork, ou você troca por um modelo permissivo, ou treina o
seu, ou o app baixa o modelo separadamente em vez de embarcá-lo. **Decida isso antes de
investir em tuning**, não depois.

Modelos `yolov8m` são grandes (~50 MB) e lentos para celular. Se a latência não fechar,
o caminho é um `yolov8n`/`yolov8s` treinado no mesmo dataset, ou quantização int8.

### Pipeline da imagem

```
bitmap original (ex. 1600×2400)
  → downscale para o input do modelo (ex. 640×640, letterbox)
  → inferência (Dispatchers.Default, nunca main)
  → NMS + filtro de confidence
  → desfaz o letterbox → normaliza 0–1
  → recicla o bitmap reduzido
```

Só a lista de boxes sobrevive. Nunca uma segunda cópia da página em memória.

### Filtro e padding

`BubbleDetectionConfig`: `confidenceThreshold` (começar em 0.35), `boxPadding`
(0.15–0.25 da menor dimensão do box), `inputSize`, `maxDetections`, `minBoxArea`
(descarta ruído minúsculo), `maxBoxArea` (descarta box que cobre a página inteira).

> **Medido depois da E4, e esta lista não sobreviveu.** `confidenceThreshold` e `inputSize`
> existem e estão medidos (0,25 e 640; o 0,35 desta seção cortava balão real). `boxPadding`
> ficou obsoleto quando o modelo passou a devolver a máscara. Os outros três foram medidos
> sobre 72 páginas e **não entram**: a maior caixa do capítulo cobre 4,45 % da página, então
> `maxBoxArea` não tem o que filtrar; as menores caixas pontuam 0,74 a 0,89, então `minBoxArea`
> apagaria balões que o modelo acerta; e a correlação entre confiança e área é 0,065, ou seja,
> a área não diz nada sobre o que é ruído. Ver a P10 em BUBBLE_ZOOM_STATUS.md.

---

## 8. Ordem de leitura

`sortBy(y)` não resolve. Estratégia:

1. Detectar painéis (classe `panel`) além de textos.
2. Ordenar painéis: agrupar por faixa horizontal (tolerância vertical ~5% da altura),
   depois dentro da faixa: LTR = esquerda→direita, RTL = direita→esquerda.
3. Associar cada balão ao painel de maior sobreposição (ou ao mais próximo, se nenhum).
4. Ordenar balões dentro do painel pela mesma regra.
5. Balões órfãos vão para o fim, ordenados pela mesma regra.

Se o modelo não der painéis, o fallback é a mesma regra de faixas aplicada direto aos
balões — pior, mas aceitável.

`BubbleOrderResolver` é **classe pura, sem Android**, com testes unitários cobrindo:
página simples, balões lado a lado, balão cruzando painéis, página sem painel detectado,
LTR e RTL espelhados.

---

## 9. Cache

- **Memória:** `LruCache<PageKey, BubblePageResult>`, ~40 páginas. Limpo ao sair do reader.
- **Disco:** JSON por página, em `cacheDir` (nunca em `filesDir`, nunca no backup do Mihon).

```json
{ "modelVersion": 1, "w": 0.231, "bubbles": [{"x":0.624,"y":0.081,"w":0.231,"h":0.142,"c":0.93}] }
```

Chave: `manga id + chapter id + page index + hash curto da imagem + modelVersion`.
O hash da imagem evita resultado errado quando a fonte troca o scan. Mudou o
`modelVersion` ⇒ entradas antigas são ignoradas e reescritas.

Cache é descartável. Perder o cache nunca pode quebrar nada.

## 10. Prefetch

Detectar `current`, `current+1`, `current+2`. **Uma inferência ativa por vez**, fila com
prioridade para a página atual. Ao virar a página, cancelar jobs que saíram da janela.
Nunca processar um capítulo inteiro de uma vez.

## 11. Métricas alvo

| Métrica | Meta | Ideal |
|---|---|---|
| Detecção da página atual | < 500 ms | < 200 ms |
| Trabalho de ML na main thread | 0 ms | 0 ms |
| FPS durante a animação | 60 | 60 |
| Acerto de ordem de leitura | > 95% em páginas comuns | — |

Medir antes de otimizar.

---

## 12. Ordem canônica das fases

Esta é a **única** lista de fases. Os prompts em `BUBBLE_ZOOM_PROMPTS.md` seguem ela.

| # | Fase | Testável no aparelho? |
|---|---|---|
| P0 | Baseline: Mihon original compilando e testado | sim |
| P1 | Mapa técnico do reader (`BUBBLE_ZOOM_ARCHITECTURE.md`) | não (só leitura) |
| P2 | `BubbleBox`, `BubbleDetection`, `BubblePageResult` + testes | não (unit tests) |
| P3 | Debug overlay com 3 boxes hardcoded | **sim — prova de coordenadas** |
| P4 | `focusOnBubble()` com animação | sim |
| P5 | `BubbleZoomController` + máquina de estados | sim |
| P6 | Integração com `moveToNext` / `moveToPrevious` | **sim — UX completa sem IA** |
| P7 | Preferência "Bubble Zoom" (off = vanilla) | sim |
| P8 | Interface `BubbleDetector` + `FakeBubbleDetector` | sim |
| P9 | Detector real (ONNX) + pipeline de imagem em background | **sim — primeiros boxes reais** |
| P10 | Confidence filtering + `BubbleDetectionConfig` | sim |
| P11 | Cache em memória + prefetch (current, +1, +2) | sim |
| P12 | Detecção de painéis + `BubbleOrderResolver` + testes | sim |
| P13 | Cache em disco com `modelVersion` | sim |
| P14 | Teclas de volume — **absorvida pela E2** (já andam pelo gancho da P6) | sim |
| P15 | Double tap seleciona o balão sob o dedo — **é a E2** | sim |
| P16 | `BubbleZoomOverlayView` (recorte visual animado) | sim |
| P17 | Debug HUD (índice, confidence, tempo, contagens) | sim |
| P18 | Dataset de 30 páginas + medição + tuning de threshold | sim |

Marcos:

- **Protótipo** = fim de P6. Prova que a integração com o reader funciona, sem uma linha de ML.
- **MVP técnico** = fim de P12. Dá para ler páginas reais com Bubble Zoom.
- **MVP utilizável** = fim de P18. Dá para usar todo dia.
- **Depois:** segmentação do balão, webtoon (tiling), OCR, tradução, TTS, GPU/NPU.

## 12.1 Harness de teste (montado no P0)

Duas coisas tornam todas as fases verificáveis automaticamente:

1. **Local source do Mihon.** As páginas de teste são empurradas para o emulador com
   `adb push` na pasta da fonte local do Mihon. Sem extensões, sem rede, sem material
   com copyright no loop de teste.
2. **Páginas sintéticas com ground truth.** `tools/make-test-pages.py` desenha páginas
   com balões em coordenadas normalizadas **conhecidas**, e grava
   `tools/test-pages/ground-truth.json` com essas coordenadas e a ordem de leitura
   correta em LTR e RTL.

Com isso, "o overlay está alinhado?" e "a ordem está certa?" deixam de ser opinião e
viram número. O agente também dirige a UI (`adb shell input tap/swipe/keyevent`), tira
screenshot (`adb exec-out screencap -p`) e **olha** o screenshot antes de dizer que
terminou.

O dataset real de 30 páginas (abaixo) continua necessário — páginas sintéticas provam a
matemática, não provam o detector.

## 13. Dataset de teste (P18)

~30 páginas em `bubble-test-pages/` (**no `.gitignore`** — material com copyright), cobrindo:
mangá P&B comum, página muito carregada, comic colorido, balões redondos, quadrados,
thought bubbles, caixas narrativas, texto sem balão, SFX gigantes, página sem diálogo,
low-res, double spread, RTL, LTR, scan torto, texto pequeno, muitos painéis, balão
cruzando painéis.

Para cada uma, anotar: quantos diálogos existem, quantos detectou, falsos positivos,
falsos negativos, ordem correta (sim/não).

## 14. Riscos conhecidos

| Risco | Mitigação |
|---|---|
| Licença GPL dos pesos impede distribuição | decidir o modelo antes de P18; considerar download separado |
| `yolov8m` lento demais no celular | medir em P9; ter plano B com `yolov8n` ou int8 |
| Coordenadas erradas após rotação/zoom | P3 existe exatamente para travar isso antes de qualquer IA |
| Reciclagem de páginas mostra balões da página errada | chavear estado por identidade da página, nunca por posição |
| Upstream do Mihon muda e o merge vira inferno | manter as alterações upstream mínimas e isoladas; rebase frequente |
| Memória: bitmaps duplicados | reciclar o bitmap reduzido logo após a inferência |
