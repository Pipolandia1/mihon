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
                ├── pageView (subsampling image view)  ← animateScaleAndCenter
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
| P14 | Teclas de volume | sim |
| P15 | Double tap seleciona o balão sob o dedo | sim |
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
