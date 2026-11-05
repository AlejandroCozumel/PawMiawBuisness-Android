# PawMiaw Business Android — porting rules

Android (Jetpack Compose) port of the iOS business app at `~/code/PawMiawNegocios`
(`PawMiawNegocios/`, pushed as PawMiawBuisness-iOS 74221cd), which itself mimics the web's
provider workspace (`~/code/pawmiaw-web/src/routes/provider*.tsx`). Same backend
(`~/code/paw-miaw-backend`). The goal: **the same screens, sheets, words and look as the iOS
business app, screen by screen, sheet by sheet.** Read `~/code/PawMiawNegocios/design/WEB-DESIGN-SPEC.md`
and `design/IOS-DESIGN-KIT.md` for why things look the way they do.

Never edit `~/code/PawMiawNegocios` or `~/code/PawMiawIos` (read only). Never boot an emulator
or simulator yourself; verify by building.

## Build

```bash
cd <your copy of the repo>
./gradlew :app:compileDebugKotlin --offline -q
```
Dependencies are cached; `--offline` is right. Done means this passes with no errors in your files.

## Layout and packages

- One Swift file → one Kotlin file with the same name (`SchedBookingSheet.swift` →
  `SchedBookingSheet.kt`). A Swift file over ~1,500 lines may be split in two Kotlin files
  (`Components.kt`, `Components2.kt`); say so at the top.
- Foundation (`core/`, `workspace/`, `design/`, `auth/`, `app/`): `package com.pawmiaw.business`.
- Modules: folder `app/src/main/java/com/pawmiaw/business/modules/<module>/`, package
  `com.pawmiaw.business.<module>` (lowercase: `clients`, `home`, `hospital`, `inbox`, `money`,
  `places`, `records`, `schedule`, `search`, `services`, `stays`, `stock`, `team`).
  Every module file starts with:
  ```kotlin
  package com.pawmiaw.business.clients

  import com.pawmiaw.business.*
  // and the other modules it uses, e.g.
  import com.pawmiaw.business.schedule.*
  ```
- Each module has `Strings.kt` with its own `L`/`LF`/`LP` reading the module's table first
  (iOS reads `<Module>.strings` by `#fileID`). A same-package function beats a star import, so
  inside the module `L("…")` is the module's:
  ```kotlin
  package com.pawmiaw.business.clients
  import com.pawmiaw.business.LocalizationManager
  import com.pawmiaw.business.localized
  fun L(key: String): String = localized(key, "Clients")
  fun LF(key: String, vararg arguments: Any?): String = LocalizationManager.format(L(key), arguments)
  fun LP(count: Int, one: String, other: String): String = LocalizationManager.format(L(if (count == 1) one else other), arrayOf(count))
  ```
  The tables are already in `app/src/main/assets/i18n/{es,en}/<Module>.json`, generated from the
  iOS `.strings`. **Use the same English keys as the Swift, character for character** (curly
  quotes, `…`, `%@`, `%d` included) — that is how the Spanish shows. Never hard-code visible text.
- Avoid `internal` (one Gradle module): use public, or `private` for file scope. Two private
  classes with the same name in one package clash: prefix with the file name only if the compiler says so.

## Keep Swift names

Types, functions, properties, enum cases and parameters keep their Swift names (lowerCamel enum
cases included: `ProviderState.done`), so a name searched finds both sides. A SwiftUI
`struct Foo: View` with `init(a:b:content:)` becomes `@Composable fun Foo(a: …, b: …, modifier: Modifier = Modifier, content: @Composable () -> Unit)`:
**same parameter names, same order, same defaults**, `modifier` after the Swift parameters and
before trailing lambdas, the `@ViewBuilder` content last. Unlabelled Swift parameters (`_ title:`)
keep their position and get the name used inside the Swift body.

## Mapping

| iOS | Android (already built) |
|---|---|
| `L("Key")`, `LF("Key %@", x)` | same (module-local in modules, see above) |
| `Theme.inkStrong`, `.surface`, `.blue`, `.canvas`… | same names on `Theme` (adaptive ones are `@Composable` getters; outside composition `Theme.inkStrongPair.resolve(dark)`) |
| `Theme.text(15, weight: .bold)` / `Theme.heading(22)` | `style = Theme.text(15, FontWeight.Bold)` / `Theme.heading(22)` (`.heavy`/`.black` → `FontWeight.ExtraBold`) |
| `Theme.wash(x, 14)` / `Theme.mix(a, 40, with: b)` | `Theme.wash(x, 14.0)` / `Theme.mix(a, 40.0, b)` |
| `.cardShadow()` | `Modifier.cardShadow(shape)` |
| `ProviderState`, `Hue`, `ChipTone` | same (enums / sealed class) |
| `Glyph("calendar", size: 16)`, `GlyphLabel` | `Glyph("calendar", 16.dp, tint = …)` (colour from `LocalContentColor` when no tint) |
| `Image("Wordmark")` / `Image("OlfatoFace")` | `painterResource(R.drawable.wordmark)` / `rememberAsyncImagePainter("file:///android_asset/img/OlfatoFace.svg")` (coil-svg) |
| `.foregroundStyle(c)` on text | `color = c`; on a group: `CompositionLocalProvider(LocalContentColor provides c)` |
| points | dp / sp 1:1 |
| `Workspace` (`@EnvironmentObject`) | `Workspace.shared` (Compose state inside) |
| `SessionStore` | `SessionStore.shared` |
| `LocalizationManager.shared` | `LocalizationManager` |
| `API.get/post/put/patch/delete` | `API.get<T>(path, query)`, `API.post<T>(path, body: Map)`, `API.postJson<T, B>(path, json)`, `put/putJson`, `patch/patchJson`, `delete<T>`, `API.send(method, path, body)` (no answer), `API.upload`, `API.multipart`, `API.download(path, query, fileName, ext)` → `File` |
| `JSONValue` | `JSONValue` = `JsonElement` with `["key"]`, `.string`, `.double`, `.int`, `.bool`, `.array`, `.obj`, `.isNull` |
| `APIError`, `errorText(error)` | same |
| `Page<T>` | `Page<T>` (`items`, `total`, `hasMore`) |
| `Codable` struct | `@Serializable data class`, same property names; `CodingKeys` → `@SerialName`; optionals nullable `= null`; custom `init(from:)` → custom `KSerializer` or decode from `JsonObject` with the same fallbacks. Unknown keys are ignored (`API.json`). |
| `Date` / `TimeZone` / `Calendar` | `java.time.Instant` / `ZoneId` / `java.time` (`LocalDate`, `ZonedDateTime`) on `Workspace.shared.timeZone` |
| `Money.format(cents)`, `DateText.*`, `NameText.*`, `.nonEmpty` | same (`core/Formatting.kt`) |
| `ObservableObject` + `@Published` | class with `var x by mutableStateOf(...)`; `@StateObject` → `remember { Foo() }` |
| `@State` | `var x by remember { mutableStateOf(...) }` |
| `@Binding var x: T` | parameter `x: MutableState<T>` (caller passes `remember { mutableStateOf(...) }` or `stateOf(get, set)` from `design/Binding.kt`) |
| `.task { }` / `.task(id:)` / `.onAppear` | `LaunchedEffect(Unit)` / `LaunchedEffect(id)` |
| `.onChange(of: x)` | `LaunchedEffect(x)` (skip the first run when Swift does) |
| `.onReceive(.pawMiawDataChanged)` | `LaunchedEffect(Unit) { AppEvents.dataChanged.collect { … } }`; post with `announceDataChanged()` |
| `Task { await … }` | `val scope = rememberCoroutineScope(); scope.launch { … }` |
| `.sheet(isPresented: $x) { S() }` | `if (x) AppSheet(onDismissRequest = { x = false }) { S() }` (`design/Sheet.kt`) |
| `.sheet(item: $item) { S($0) }` | `item?.let { AppSheet(onDismissRequest = { item = null }) { S(it) } }` |
| `@Environment(\.dismiss)` | `val dismiss = LocalDismiss.current` → `dismiss()` (closes the sheet, or pops a pushed page) |
| `.interactiveDismissDisabled(x)` | `InteractiveDismissDisabled(x)` inside the sheet |
| `.presentationDetents([...])` | `Modifier.sheetPresentation(setOf(PresentationDetent.large))` on the sheet's root |
| `NavigationLink(value: ProviderRoute.x(id)) { label }` | `val nav = LocalNavigator.current` … `Modifier.clickable { nav.push(ProviderRoute.x(id)) }` |
| `ProviderRoute`, `ProviderSection`, prefills | same (`app/Sections.kt`) |
| `@Environment(\.horizontalSizeClass)` | `LocalSizeClass.current == SizeClass.regular` |
| `@Environment(\.openURL)` | `val open = rememberOpenURL(); open("tel:…")` |
| `@Environment(\.colorScheme) == .dark` | `LocalIsDark.current` |
| `.alert` / `.confirmationDialog` | the iOS kit's own `ConfirmSheet` where the Swift uses it; a plain `.confirmationDialog` → `ConfirmSheet` too (the web never uses system dialogs) |
| `Menu { … } label: { … }` | `DropdownMenu` anchored to the label (`design/Menus.kt` once ported: `AppMenu`) |
| `ScrollView { VStack(spacing: 12) }` | `Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp))` |
| `List` / `LazyVStack` | `LazyColumn` |
| `HStack/VStack/ZStack(alignment:spacing:)` | `Row/Column/Box` with `Arrangement.spacedBy` / alignment |
| `.frame(maxWidth: .infinity, alignment: .leading)` | `Modifier.fillMaxWidth()` + alignment on the parent / `wrapContentWidth(Alignment.Start)` |
| `RoundedRectangle(cornerRadius: r, style: .continuous)` | `RoundedCornerShape(r)` |
| `Capsule()` | `RoundedCornerShape(50)` / `CircleShape` |
| `.strokeBorder(c, lineWidth: 1)` | `Modifier.border(1.dp, c, shape)` |
| `withAnimation(.spring)` / `.transition` | `animate*AsState` / `AnimatedVisibility` / `AnimatedContent` with similar timing |
| `UIPasteboard` | `LocalClipboardManager` |
| `ShareLink` / share sheet | `Intent.ACTION_SEND` (`design/ShareSheet.kt` once ported) |
| `PhotosPicker` / camera | `rememberLauncherForActivityResult(PickVisualMedia())` / `TakePicture()` |
| haptics | `LocalHapticFeedback.current` |

## Rules

- Port **everything** the Swift file has: every state (loading, empty, error, offline), every
  sheet, button, menu, confirmation, animation and accessibility label (`contentDescription`,
  `Modifier.semantics`). Read the Swift line by line. Keep the Swift comments that explain a rule
  (shortened is fine).
- Same layout numbers as iOS (paddings, sizes, radii, font sizes, colours).
- If you need a symbol another area owns and it is not built yet, add the **smallest stand-in**
  in `_stubs_<yourarea>.kt` placed in the **owner's package** (e.g.
  `modules/schedule/_stubs_home.kt` with `package com.pawmiaw.business.schedule`), with the
  exact Swift name and the Kotlin signature these rules give it, and a `// STUB: <Swift file>`
  line. The merge deletes every `_stubs_*` file, so never put real code there. List your stubs in
  your report.
- Don't edit files you don't own. Missing icon names: `Glyph` draws a dashed circle; list them in
  your report instead of editing `GlyphNames.kt`.
- No secrets, no keys, no real credentials anywhere.
