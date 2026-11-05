# PawMiaw Business — Android

The business workspace of PawMiaw (PawMiaw Negocios) for Android phones and tablets, in Kotlin and
Jetpack Compose. It is the twin of the iOS business app
([PawMiawBuisness-iOS](https://github.com/AlejandroCozumel/PawMiawBuisness-iOS)), screen by screen and
sheet by sheet, and talks to the same backend (`paw-miaw-backend`). The iOS app in turn mirrors the
web's provider workspace.

- Package / application id: `com.pawmiaw.business`, min SDK 30, target 36.
- Phone: a white top bar (sidebar button, wordmark, places, search, bell) and the sidebar sliding in
  from the left, grouped Operación · Cobros · Negocio; no tab bar. Tablet (600 dp and wider): the
  sidebar docked beside the page; sheets are centred cards from 840 dp.
- English and Spanish, chosen in the account menu (strings in `app/src/main/assets/i18n`, generated
  from the iOS `.strings` by `scripts/strings-from-ios.py`).

## Build and run

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

A debug build talks to the local backend as the emulator sees it (`http://10.0.2.2:3000/api`). On a
phone over USB: `adb reverse tcp:3000 tcp:3000` and build with
`-PpawmiawApiBaseUrl=http://localhost:3000/api`. A release build uses `https://api.pawmiaw.com/api`.

Debug builds only: a design review can open the app signed in on a page with intent extras
(`PAWMIAW_DEBUG_SESSION_TOKEN`, `PAWMIAW_DEBUG_SECTION` = a `ProviderSection` raw value,
`PAWMIAW_API_BASE_URL`); see `core/APIConfig.kt`.

## Layout

| Folder | What |
|---|---|
| `core/` | API client, session, localization, formatting, barcodes, photo upload, visit location |
| `workspace/` | the business, its locations and permissions |
| `design/` | the design kit: Theme, Lucide glyphs, buttons, fields, menus, sheets (`ProviderModal`), module header, client/pet picker, shared cards |
| `app/` | activity, root, shell (top bar, sidebar), sections and routes |
| `auth/` | sign in, business chooser |
| `modules/<area>/` | Clients, Home, Hospital, Inbox, Money, Places, Records, Schedule, Search, Services, Stays, Stock, Team — one Kotlin file per iOS Swift file, same names |

How the Swift was carried over (names, bindings, sheets, navigation): `PORTING.md`.
