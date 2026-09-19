# Bubble Zoom — Mapa técnico do reader

> Fase P1. Auditoria somente-leitura do repositório, sem alterar código de aplicação.
> Todos os caminhos, classes e assinaturas abaixo foram lidos **deste** repositório
> (Mihon `5848ffb4c`), não de memória nem do Tachiyomi upstream.
>
> Os números de linha valem para o commit acima e envelhecem; os nomes não.

Raiz do reader: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/`
Abreviada como `R/` no restante do documento.

---

## 1. Onde a imagem decodificada de uma ReaderPage fica disponível

A cadeia é:

```
PagerPageHolder.init
  └── scope.launch { loadPageAndProcessStatus() }      R/viewer/pager/PagerPageHolder.kt:66
        └── setImage()                                  :147
              └── ReaderPageImageView.setImage(source, isAnimated, config)
                    └── setNonAnimatedImage(...)         R/viewer/ReaderPageImageView.kt:272
                          └── SubsamplingScaleImageView.setOnImageEventListener(...)
                                └── onReady()            ← A IMAGEM EXISTE AQUI
                                      ├── setupZoom(config)
                                      └── ReaderPageImageView.onImageLoaded()
                                            └── PagerPageHolder.onImageLoaded()   :253
```

**O ponto de ciclo de vida exato** para disparar a detecção é o override de
`onImageLoaded()` em `PagerPageHolder` (`R/viewer/pager/PagerPageHolder.kt:253`). Nesse
instante a `SubsamplingScaleImageView` já está `isReady`, e portanto `sWidth`, `sHeight`,
`scale`, `minScale` e as conversões de coordenada já são válidas.

Assinaturas relevantes:

```kotlin
// R/viewer/ReaderPageImageView.kt
open class ReaderPageImageView(... private val isWebtoon: Boolean = false) : FrameLayout {
    var onImageLoaded: (() -> Unit)?
    var onImageLoadError: ((Throwable?) -> Unit)?
    var onScaleChanged: ((newScale: Float) -> Unit)?
    var onViewClicked: (() -> Unit)?
    @CallSuper open fun onImageLoaded()
    @CallSuper open fun onScaleChanged(newScale: Float)
    fun recycle()
}

// R/viewer/pager/PagerPageHolder.kt
class PagerPageHolder(
    readerThemedContext: Context,
    val viewer: PagerViewer,
    val page: ReaderPage,
) : ReaderPageImageView(readerThemedContext), ViewPagerAdapter.PositionableView
```

**A imagem decodificada em si não é exposta.** A `SubsamplingScaleImageView` recebe um
`ImageSource.inputStream(...)` ou `ImageSource.bitmap(...)` e passa a gerenciar os tiles
internamente. Para a detecção, o bitmap terá de ser decodificado **separadamente** a
partir de `page.stream` — o que é desejável de qualquer forma, já que a inferência precisa
de uma cópia reduzida e não da imagem em resolução plena (regra de bitmaps do `CLAUDE.md`).

---

## 2. Qual view desenha a página, e os dois caminhos

`ReaderPageImageView` é um `FrameLayout` que hospeda **uma** de duas views filhas,
escolhida pelo tipo da imagem:

| Caminho | Condição | View (pager) | View (webtoon) |
|---|---|---|---|
| Não animada | `drawable !is Animatable` | `SubsamplingScaleImageView` | `WebtoonSubsamplingImageView` |
| Animada | GIF / WebP animado | `PhotoView` | `AppCompatImageView` |

Decidido em `setImage(...)` (`R/viewer/ReaderPageImageView.kt:142` e `:152`), que chama
`prepareNonAnimatedImageView()` (`:225`) ou `prepareAnimatedImageView()` (`:331`).

A view fica em:

```kotlin
private var pageView: View? = null     // R/viewer/ReaderPageImageView.kt:59
```

**`private`, não `protected`.** Em Kotlin isso significa que nem mesmo `PagerPageHolder`,
que herda de `ReaderPageImageView`, consegue alcançá-la. Ver a seção 12 — é daqui que sai
a única alteração upstream realmente inevitável.

Biblioteca: `com.github.mihonapp:subsampling-scale-image-view` (fork do Mihon, versão
`94915e6f73` em `gradle/libs.versions.toml:71`), pacote
`com.davemorrissey.labs.subscaleview`.

---

## 3. Conversão de coordenadas e animação de escala/centro

A API é a da `SubsamplingScaleImageView`, e o `CLAUDE.md` exige usá-la em vez de
reimplementar a matemática:

```kotlin
fun viewToSourceCoord(vxy: PointF): PointF?             // view  → imagem (source)
fun viewToSourceCoord(vx: Float, vy: Float, target: PointF): PointF?
fun sourceToViewCoord(sxy: PointF): PointF?            // imagem → view
fun sourceToViewCoord(sx: Float, sy: Float, target: PointF): PointF?
val sWidth: Int; val sHeight: Int                      // dimensões da imagem original
val scale: Float; val center: PointF?
val isReady: Boolean                                   // antes disso nada acima vale
```

Animação — este é exatamente o alvo da fase P4, e o próprio Mihon já usa o padrão em
`landscapeZoom()` (`R/viewer/ReaderPageImageView.kt:133`):

```kotlin
animateScaleAndCenter(targetScale, point)!!
    .withDuration(500)
    .withEasing(EASE_IN_OUT_QUAD)
    .withInterruptible(true)
    .start()
```

E em `pan()` (`:215`) há a variante só de centro:

```kotlin
view.animateCenter(target)!!
    .withEasing(EASE_OUT_QUAD)
    .withDuration(250)
    .withInterruptible(true)
    .start()
```

`withInterruptible(true)` é o que permite ao usuário cancelar o zoom de um balão tocando
ou arrastando — comportamento desejável e que deve ser preservado.

⚠️ **Correção aplicada na P3.** Este documento listava `sourceToViewRect(sRect, vTarget)`.
**Esse método não existe** neste fork — verificado com `javap` sobre o `classes.jar` do
artefato `com.github.mihonapp:subsampling-scale-image-view:94915e6f73`. Só existem as
variantes por ponto. Converta os dois cantos separadamente.

Sobre quando o estado é notificado, também verificado na fonte da biblioteca:
`sendStateChanged` é chamado de `onTouchEvent` e de dentro do passo de animação em
`onDraw`, e só dispara quando `scale` ou `vTranslate` realmente mudaram. Ou seja, o
`OnStateChangedListener` notifica **quadro a quadro** durante pan, pinça e animação.

**Coordenadas normalizadas (0–1) do `BubbleBox` convertem para source multiplicando por
`sWidth`/`sHeight`.** Nunca para coordenadas de view diretamente: `sourceToViewCoord` cuida
disso e continua correto após rotação e mudança de escala.

---

## 4. minScale e maxScale

- **`minScale`** é calculado pela biblioteca a partir de `minimumScaleType` e do tamanho da
  view. O Mihon configura em `setNonAnimatedImage()` (`R/viewer/ReaderPageImageView.kt:274`):
  `setMinimumScaleType(config.minimumScaleType)` — padrão `SCALE_TYPE_CENTER_INSIDE`
  (`Config.minimumScaleType`, `:414`) — e `setMinimumDpi(1)`, com o comentário de que é
  "just so that very small image will be fit for initial load".
- **`maxScale`** é atribuído pelo Mihon em `setupZoom()` (`:253`):

```kotlin
maxScale = scale * MAX_ZOOM_SCALE          // MAX_ZOOM_SCALE = 5F  (:423)
setDoubleTapZoomScale(scale * 2)
```

Note que ambos derivam do `scale` **no momento do `onReady`**, isto é, do scale de encaixe
inicial. Um zoom de balão que ultrapasse `maxScale` será silenciosamente limitado pela
biblioteca — o `BubbleZoomConfig` precisa respeitar esse teto, ou o balão não será
enquadrado como esperado.

---

## 5. Navegação por toque e quem é dono de moveToNext/moveToPrevious

Dono: **`PagerViewer`** (`R/viewer/pager/PagerViewer.kt:31`), classe abstrata.

```kotlin
open fun moveToNext()          // :319  → moveRight()
open fun moveToPrevious()      // :326  → moveLeft()
protected open fun moveRight() // :333
protected open fun moveLeft()  // :347
protected open fun moveUp()    // :361  → moveToPrevious()
protected open fun moveDown()  // :368  → moveToNext()
```

`R2LPagerViewer` (`R/viewer/pager/PagerViewers.kt`) inverte apenas os dois primeiros:
`moveToNext() = moveLeft()`, `moveToPrevious() = moveRight()`.

O despacho do toque está em `PagerViewer.kt:114`:

```kotlin
when (config.navigator.getAction(pos)) {
    NavigationRegion.MENU  -> activity.toggleMenu()
    NavigationRegion.NEXT  -> moveToNext()
    NavigationRegion.PREV  -> moveToPrevious()
    NavigationRegion.RIGHT -> moveRight()      // ← NÃO passa por moveToNext
    NavigationRegion.LEFT  -> moveLeft()       // ← NÃO passa por moveToPrevious
}
```

**Este é o achado mais importante da P1.** Os layouts de navegação usam regiões diferentes:

| Layout | Arquivo | Regiões |
|---|---|---|
| `LNavigation` | `R/viewer/navigation/LNavigation.kt` | `PREV`, `NEXT` |
| `KindlishNavigation` | `.../KindlishNavigation.kt` | `PREV`, `NEXT` |
| `EdgeNavigation` | `.../EdgeNavigation.kt` | `PREV`, `NEXT` |
| **`RightAndLeftNavigation`** | `.../RightAndLeftNavigation.kt` | **`LEFT`, `RIGHT`** |
| `DisabledNavigation` | `.../DisabledNavigation.kt` | — |

E o padrão para pagers horizontais é justamente o que **não** passa por `moveToNext`:

```kotlin
// R/viewer/pager/PagerConfig.kt:131
override fun defaultNavigation(): ViewerNavigation {
    return when (viewer) {
        is VerticalPagerViewer -> LNavigation()
        else -> RightAndLeftNavigation()
    }
}
```

A preferência `navigationModePager` (`R/setting/ReaderPreferences.kt:176`) tem padrão `0`,
que mapeia para `defaultNavigation()` em `PagerConfig.updateNavigation` (`:139`).

**Consequência:** interceptar apenas `moveToNext()`/`moveToPrevious()`, como o diagrama da
spec propõe, capturaria as teclas de volume mas **não o toque na configuração padrão**.
Ver a seção 13.

---

## 6. Teclas de volume

Tratadas em `PagerViewer.handleKeyEvent(event: KeyEvent): Boolean`
(`R/viewer/pager/PagerViewer.kt:387`), chamada pela `ReaderActivity`:

```kotlin
KeyEvent.KEYCODE_VOLUME_DOWN -> {
    if (!config.volumeKeysEnabled || activity.viewModel.state.value.menuVisible) return false
    else if (isUp) { if (!config.volumeKeysInverted) moveDown() else moveUp() }
}
KeyEvent.KEYCODE_VOLUME_UP -> { /* espelhado */ }
```

`moveDown()`/`moveUp()` chamam `moveToNext()`/`moveToPrevious()`, então **as teclas de
volume passam pelo ponto que a spec propõe** — ao contrário do toque padrão.

Duas guardas a preservar na fase P14: `config.volumeKeysEnabled` e o fato de que as teclas
são ignoradas com o menu aberto. O evento é consumido no `ACTION_UP`.

Também tratados aqui: DPAD, Page Up/Down e `KEYCODE_MENU`. `KEYCODE_DPAD_RIGHT/LEFT` com
Ctrl chamam `moveToNext`/`moveToPrevious`; sem Ctrl, `moveRight`/`moveLeft`.

---

## 7. Double-tap zoom

**Não é código do Mihon.** É a própria `SubsamplingScaleImageView`, configurada em
`prepareNonAnimatedImageView()` (`R/viewer/ReaderPageImageView.kt:236`):

```kotlin
setDoubleTapZoomStyle(SubsamplingScaleImageView.ZOOM_FOCUS_CENTER)
```

com escala e duração definidas em `setupZoom()` e `setNonAnimatedImage()`:

```kotlin
setDoubleTapZoomScale(scale * 2)
setDoubleTapZoomDuration(config.zoomDuration.getSystemScaledDuration())
```

No caminho animado o Mihon **substitui** o comportamento, com um
`GestureDetector.SimpleOnGestureListener` próprio em `prepareAnimatedImageView()` (`:342`)
que alterna entre 1x e 2x.

**Implicação para a P15:** para interceptar o double tap no caminho principal será preciso
instalar um listener antes da biblioteca, ou usar
`SubsamplingScaleImageView.setOnDoubleTapListener`/`GestureDetectorWithLongTap`
(`R/viewer/GestureDetectorWithLongTap.kt`, já existente no repositório). Isso não foi
prototipado nesta fase e deve ser decidido na P15.

---

## 8. Preferências e UI de configurações do reader

- Preferências: `R/setting/ReaderPreferences.kt`, classe `ReaderPreferences`, injetada
  via Metro (`@Inject @SingleIn(AppScope::class)`).
- UI: `app/src/main/java/eu/kanade/presentation/reader/settings/`
  — `ReaderSettingsDialog.kt`, `ReadingModePage.kt`, `GeneralSettingsPage.kt`,
  `ColorFilterPage.kt`.
- Tela de configurações global do reader:
  `app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsReaderScreen.kt`.
- ViewModel: `R/setting/ReaderSettingsViewModel.kt`.

Padrão de declaração a seguir na fase P7:

```kotlin
val defaultReadingMode: Preference<Int> =
    preferenceStore.getInt("pref_default_reading_mode_key", ReadingMode.RIGHT_TO_LEFT.flagValue)
val navigationModePager: Preference<Int> =
    preferenceStore.getInt("reader_navigation_mode_pager", 0)
```

A preferência do Bubble Zoom deve morar no pacote `bubblezoom`, e não em
`ReaderPreferences`, se for possível injetá-la separadamente — ver seção 12.

---

## 9. Reciclagem de páginas e preload

**Reciclagem.** O adapter é `PagerViewerAdapter` sobre `ViewPagerAdapter`, e a identidade
de uma view é a **própria página**, não a posição:

```kotlin
// R/viewer/pager/PagerPageHolder.kt:45
override val item get() = page
```

Isso é uma boa notícia para a armadilha que o `CLAUDE.md` descreve: chavear estado por
`ReaderPage` (identidade) é o caminho correto e o próprio framework já pensa assim.

Ciclo de vida do holder:

```kotlin
init { loadJob = scope.launch { loadPageAndProcessStatus() } }   // :66
override fun onDetachedFromWindow() {                            // :73
    super.onDetachedFromWindow()
    loadJob?.cancel()
    loadJob = null
}
```

⚠️ **`private val scope = MainScope()` (`:58`) nunca é cancelado.** Apenas `loadJob` é.
Um job de detecção lançado ingenuamente nesse scope **vazaria** ao reciclar a página, o
que o `CLAUDE.md` classifica como bug reportável. A fase P11 precisa de um job próprio,
cancelado em `onDetachedFromWindow()`, ou de um scope próprio que seja cancelado ali.

**Quantas páginas existem ao mesmo tempo:**

```kotlin
pager.offscreenPageLimit = 1     // R/viewer/pager/PagerViewer.kt:101
```

Ou seja, **apenas `current-1`, `current` e `current+1` têm holder vivo.**

**Preload.** O que `PagerViewer` chama de preload é preload de **capítulo**, não de página:
`checkAllowPreload()` (`:203`) e `onReaderPageSelected()` (`:226`) pedem
`activity.requestPreloadChapter(...)` quando faltam menos de 5 páginas para o fim do
capítulo. O carregamento das imagens vizinhas é consequência do `offscreenPageLimit`.

---

## 10. Como o reader sabe se está em LTR ou RTL

Não existe um booleano `isRtl`. A direção está codificada no **tipo do viewer**:

```kotlin
// R/setting/ReadingMode.kt:72
fun toViewer(preference: Int?, activity: ReaderActivity): Viewer {
    if (activity.appGraph.basePreferences.highQualityRenderer.get()) { /* WebGPU */ }
    return when (fromPreference(preference)) {
        LEFT_TO_RIGHT -> L2RPagerViewer(activity)
        RIGHT_TO_LEFT -> R2LPagerViewer(activity)
        VERTICAL      -> VerticalPagerViewer(activity)
        WEBTOON       -> WebtoonViewer(activity)
        CONTINUOUS_VERTICAL -> WebtoonViewer(activity, isContinuous = false)
        DEFAULT -> throw IllegalStateException("Preference value must be resolved: $preference")
    }
}
```

O teste idiomático no repositório é `viewer is R2LPagerViewer` — usado, por exemplo, em
`ReaderActivity.kt:479` e em `PagerViewerAdapter.kt:102`.

**O padrão do app é RTL:**

```kotlin
// R/setting/ReaderPreferences.kt:60
val defaultReadingMode: Preference<Int> = preferenceStore.getInt(
    "pref_default_reading_mode_key",
    ReadingMode.RIGHT_TO_LEFT.flagValue,
)
```

E o RTL é implementado **invertendo a lista do adapter**, não a view:

```kotlin
// R/viewer/pager/PagerViewerAdapter.kt:102
if (viewer is R2LPagerViewer) { newItems.reverse() }
```

⚠️ **Portanto, em RTL o índice do adapter não é o índice de leitura.** Estado do Bubble
Zoom indexado por posição de adapter mostrará balões da página errada em RTL — que é o
modo padrão. Mais um argumento para chavear por `ReaderPage`.

---

## 11. Páginas duplas / spreads

No caminho do pager existem **três** mecanismos distintos, todos em `PagerPageHolder`:

| Mecanismo | Preferência | Método |
|---|---|---|
| Split de página dupla | `dualPageSplitPaged` (`ReaderPreferences.kt:115`) | `splitInHalf()` (`:221`) |
| Inversão do split | `dualPageInvertPaged` (`:117`) | idem |
| Rotacionar para caber | `dualPageRotateToFit` (`:123`) | `rotateDualPage()` (`:211`) |

O split cria uma `InsertPage`:

```kotlin
// R/model/InsertPage.kt
class InsertPage(val parent: ReaderPage) : ReaderPage(parent.index, parent.url, parent.imageUrl)
```

inserida no adapter por `PagerViewer.onPageSplit(currentPage, newPage)` (`:446`) e
removida por `cleanupPageSplit()` (`:453`). O adapter trata a ordem do insert em RTL
explicitamente (`PagerViewerAdapter.kt:168`).

⚠️ **Isto contradiz a premissa da spec.** O `CLAUDE.md` diz "um spread é uma imagem só;
trate como uma página". Com `dualPageSplitPaged` ligado, **uma imagem vira duas
`ReaderPage`, cada uma exibindo metade**. Coordenadas normalizadas em relação à imagem
original ficariam deslocadas por um fator de 2 em x e por um offset de meia página na
segunda metade.

Recomendação: no MVP, **desligar o Bubble Zoom quando a página for `InsertPage` ou quando
`config.dualPageSplit` estiver ativo**, exatamente como se faz com imagens animadas. É uma
linha de guarda, não um caso a resolver.

Já `dualPageView` (`NEVER`/`ALWAYS`/`WIDE`, `ReaderPreferences.kt:140`) — duas páginas lado
a lado de verdade — é usado **somente pelo `WebGpuViewer`** (`WebGpuConfig.kt:61`,
`WebGpuViewer.kt:759`). Não afeta o caminho do pager.

---

## 12. Conjunto mínimo de arquivos upstream a modificar

Ordenado do inevitável ao opcional.

| Arquivo | Alteração | Justificativa |
|---|---|---|
| `R/viewer/ReaderPageImageView.kt` | Tornar `pageView` acessível, ou adicionar **um** método `fun focusOn(sourceRect: RectF, config: BubbleZoomConfig)` que delega para `animateScaleAndCenter` | `pageView` é `private`; nem a subclasse alcança. Sem isso não há como animar o zoom a partir do pacote `bubblezoom`. Inevitável. |
| `R/viewer/pager/PagerPageHolder.kt` | Uma chamada em `onImageLoaded()` e uma em `onDetachedFromWindow()` | É o único ponto onde a imagem fica pronta e o único onde a reciclagem é observável. Duas linhas. |
| `R/viewer/pager/PagerViewer.kt` | Um desvio no `when` do despacho de toque (`:114`) e nas bordas de `moveToNext`/`moveToPrevious` | Ver seção 5: o toque padrão não passa por `moveToNext`. Sem tocar aqui, o Bubble Zoom não responde ao toque na configuração padrão. |
| `R/setting/ReaderPreferences.kt` | Uma `Preference<Boolean>` | Só se não for viável um `BubbleZoomPreferences` injetado à parte. **Verificar na P7** antes de tocar neste arquivo. |
| `eu/kanade/presentation/reader/settings/` | Uma linha de item de UI | Para expor a preferência. Provavelmente inevitável. |

Total estimado: **4 a 5 arquivos, todos com alterações de poucas linhas**, cada uma
delegando para o pacote `bubblezoom`. Isso satisfaz a regra dos 90% do `CLAUDE.md`.

---

## 13. Arquitetura de integração proposta

Consistente com a spec, com uma correção no ponto de acoplamento.

```
PagerViewer
  ├── despacho de toque (:114)  ──┐
  ├── moveToNext / moveToPrevious ┤──► BubbleZoomController.onNavigate(direction): Boolean
  └── handleKeyEvent (volume)   ──┘        true  = o controller consumiu (foi para o próximo balão)
                                           false = navegação normal de página prossegue
PagerPageHolder
  ├── onImageLoaded()        ──► BubbleZoomController.onPageReady(page, view)
  └── onDetachedFromWindow() ──► BubbleZoomController.onPageRecycled(page)

BubbleZoomController
  ├── BubbleZoomState          (página atual, índice do balão, ativo/inativo)
  ├── BubbleZoomCache          (memória → disco, chaveado por ReaderPage + hash + modelVersion)
  ├── BubbleDetector           (interface; Fake em P8, ONNX em P9)
  ├── BubbleOrderResolver      (puro, sem Android, testável na JVM)
  └── ReaderPageImageView.focusOn(sourceRect)  ← o único ponto que toca a view
```

**O contrato central é `onNavigate(direction): Boolean`.** Um booleano de "consumi este
evento" é o que permite que, ao acabarem os balões, a navegação normal de página continue
sem nenhum caso especial — o requisito 6 da definição de sucesso.

Guardas para desligar o Bubble Zoom, todas checadas antes de qualquer trabalho:

1. preferência desligada;
2. `viewer !is PagerViewer` (webtoon, contínuo);
3. `highQualityRenderer` ligado (caminho WebGPU);
4. imagem animada (a `pageView` não é `SubsamplingScaleImageView`);
5. `page is InsertPage` ou split de página dupla ativo;
6. página sem balões detectados.

---

## 14. Divergências entre a spec e este repositório

Numeradas para referência nas fases seguintes.

**D1 — O diagrama da spec acopla no lugar errado.**
`docs/BUBBLE_ZOOM_SPEC.md` seção 5 mostra `PagerViewer ──── moveToNext / moveToPrevious` como
o ponto de integração. Na navegação **padrão** (`RightAndLeftNavigation`), o toque vai
direto a `moveRight()`/`moveLeft()` e nunca passa por ali. O gancho precisa ficar no
despacho de `NavigationRegion` (`PagerViewer.kt:114`), **e** em `moveToNext`/`moveToPrevious`
para cobrir volume e DPAD. Afeta **P6**.

**D2 — O prefetch de `current+2` não tem holder.**
A spec seção 10 pede detecção de `current`, `current+1` e `current+2`. Com
`offscreenPageLimit = 1` só existem `current±1`. A detecção de `current+2` precisa partir
dos dados da página (`ReaderPage.stream`), nunca de uma view. Afeta **P11**.

**D3 — Existe um segundo renderizador que a spec não menciona.**
`WebGpuViewer` / `WebGpuViewerContinuous` (`R/viewer/webgpu/`) substituem todo o caminho de
renderização quando `basePreferences.highQualityRenderer` está ligado. O padrão é `false`,
então o alvo do Bubble Zoom continua sendo o padrão — mas é mais um caminho onde a feature
precisa ficar **desligada sem quebrar**, como já se exige de imagens animadas. Afeta **P7**.

**D4 — "Um spread é uma imagem só" não é verdade quando o split está ligado.**
Ver seção 11. Com `dualPageSplitPaged`, uma imagem vira duas `ReaderPage`. Sugestão:
tratar como guarda de desligamento no MVP em vez de caso a resolver. Afeta **P2** e **P6**.

**D5 — O padrão do app é RTL, e o RTL inverte a lista do adapter.**
A spec trata LTR e RTL como simétricos, o que é verdade para a ordenação, mas o índice do
adapter em RTL é o inverso do índice de leitura. Qualquer estado por posição quebra.
Reforça a decisão de chavear por `ReaderPage`. Afeta **P11**, **P12**, **P13**.

**D6 — O scope do `PagerPageHolder` nunca é cancelado.**
`private val scope = MainScope()` (`:58`) sobrevive à reciclagem; só `loadJob` é cancelado.
Um job de detecção lançado nele vaza. Afeta **P11**.

**D7 — O teto de zoom é `minScale * 5`.**
`maxScale = scale * 5F` no `onReady`. Um balão pequeno pode exigir mais que isso para
preencher a tela; a biblioteca limitará em silêncio. O `BubbleZoomConfig` precisa de um
alvo de zoom que respeite `maxScale`, e a P4 deve verificar o caso de um balão muito
pequeno. Afeta **P4** e **P10**.

---

## 15. O que esta fase NÃO apurou

Registrado para não virar suposição depois.

- **Como interceptar o double tap** sem brigar com a `SubsamplingScaleImageView`. Há um
  `GestureDetectorWithLongTap` no repositório que pode servir, mas nada foi prototipado.
  Decidir na **P15**.
- **Se o Bubble Zoom pode ter um `BubbleZoomPreferences` injetado à parte** via Metro, sem
  tocar em `ReaderPreferences`. Depende de como o grafo do Metro é montado. Verificar na
  **P7**.
- **O custo real de decodificar uma cópia reduzida** da página em paralelo à
  `SubsamplingScaleImageView`. É a métrica de < 500 ms da spec e só se mede na **P9**.
- **`VerticalPagerViewer`**: é um `PagerViewer`, então herdaria o Bubble Zoom de graça,
  mas sua navegação padrão é `LNavigation` e o eixo é outro. Não avaliado. Decidir na **P6**.
