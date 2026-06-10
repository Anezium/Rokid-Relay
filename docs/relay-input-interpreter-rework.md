# Relay input interpreter rework

## Probleme

La logique d'input des glasses fonctionne, mais elle reste dispersee entre
`RelayAccessibilityService`, `MainActivity` et plusieurs petits helpers. Les
deux entrees Android ne passent pas encore par une decision unique, ce qui rend
les regressions faciles: un correctif peut proteger le chemin Accessibility
sans proteger le chemin Activity, ou inversement.

Les timings sont aussi eparpilles: debounce directionnel, pagination, double
tap inbox, pending direction apres two-finger, clear du volume systeme. Enfin,
les decisions metier sont melangees aux effets Android: wake locks, volume,
`Handler.postDelayed`, `RelayHudController` et `RelayBridge`.

## Proposition

Ajouter un `RelayInputInterpreter` Kotlin pur:

```kotlin
RelayInputInterpreter.handle(event, snapshot, nowMs): RelayInputDecision
```

Les services Android deviennent de petits adaptateurs:

- convertir `KeyEvent` et broadcasts two-finger en evenements normalises;
- fournir un snapshot de l'etat HUD;
- executer les actions retournees;
- programmer ou annuler les timers via `Handler`.

L'interpreter reutilise les briques existantes: `RelayDirectionKeyMapper`,
`RelayDirectionDebouncer`, `RelayInboxDirectionGate`,
`RelayInputComboBuffer` et `RelayInputSettings`.

## Modele

Evenements:

- `Direction(source, direction)` avec `source = DirectionKey | TwoFingerBroadcast`
- `Confirm`
- `Back`
- `KeyUp`
- `TimerFired(SingleTap | PendingInboxDirection | ClearCommandVolume)`

Snapshot:

- `inboxOpen`, `inboxDetailOpen`
- `voiceActive`, `voiceReviewing`
- `hasNotification`, `hasPagedNotification`
- `directionKeysEnabled`, `twoFingerCommandsEnabled`
- `inputCombo`

Actions:

- `StartVoice`, `CancelVoice`
- `OpenInbox`, `OpenInboxDetail`, `BackInInbox`
- `NavigateInbox(delta)`
- `PageInboxDetail(delta)`, `PageNotification(delta)`
- `HideNotification`
- `KeepReplyScreenOn(durationMs)`
- `CaptureCommandVolume`, `RestoreCommandVolumeSoon`
- `ScheduleTimer`, `CancelTimer`
- `OpenAccessibilitySettings`
- `ConsumeOnly`, `PassThroughSystem`

## Migration

1. Ajouter `RelayInputInterpreter` et ses tests unitaires sans changer le
   comportement Android.
2. Ajouter un builder de snapshot depuis `RelayHudController`.
3. Migrer `MainActivity` en premier.
4. Migrer `RelayAccessibilityService` ensuite, en gardant dans le service les
   details Android: key grabbing, overlay, wake locks, volume, `Handler`.
5. Supprimer les champs dupliques dans Activity/Service apres parite.
6. Garder les tests helpers existants comme garde-fous bas niveau.

## Regressions a couvrir

- `DPAD_RIGHT` puis `DPAD_DOWN` produit un seul mouvement.
- `DPAD_LEFT` puis `DPAD_UP` produit un seul mouvement.
- Les directions opposees restent acceptees dans la fenetre de debounce.
- En mode two-finger hors inbox, les direction keys normales ne pilotent pas Relay.
- En inbox ouverte, les direction keys restent actives meme en mode two-finger.
- Un two-finger suivi d'une direction key parasite ne navigue pas l'inbox.
- Une pending inbox direction ne s'applique qu'apres le delai de confirmation.
- Single tap inbox ouvre le detail; single tap dans le detail demarre la reponse.
- Double tap inbox declenche le back et annule les pending directions.
- Voice reviewing: confirm demarre, back annule.
- Notification paginee: direction page; notification non paginee: direction
  demarre voice.
- Combo incomplet ou double par alias physique ne matche pas.
- Combo complet apres vrais swipes physiques ouvre l'inbox.
- Activity et Accessibility produisent les memes actions pour directions,
  confirm et back.
