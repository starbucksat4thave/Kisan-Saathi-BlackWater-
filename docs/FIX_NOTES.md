# Fix notes, 15 November 2026

What changed in this pass, what you must do in the Firebase console, and what could not be tested.

## What changed

| Area | Before | Now |
|---|---|---|
| Chat when Gemini is unreachable | Returned one of four canned Urdu answers, unlabelled, including a urea dose | Answers from the on-device engine using today's real field data, in Urdu or English, labelled "offline answer". No doses. |
| Chat when Gemini is reachable | Prompt had a fixed crop stage | Prompt carries today's real soil moisture, rain, forecast and crop stage, and forbids pesticide names and doses |
| Photo scan when Gemini is unreachable | Showed "below threshold, spray not justified, safe" | Shows "Photo could not be analysed" in amber and says nothing is known about the leaf |
| Photo scan input | A generated green square | Real camera photo or a gallery picture |
| Photo scan output | Guessed from keywords in free text | Assistant must reply in a fixed format; an unreadable photo is reported as "unclear" |
| Voice input | Mic button waited 2.5 seconds and inserted a fixed question | Uses the phone's speech recogniser in Urdu or English |
| "Escalate to Agri Officer" | Showed "Sent" without sending anything | Opens the phone's share sheet with the result |
| Field Health: status card and zone map | Fixed text and invented per-zone moisture | Today's real conditions and the SoilGrids soil texture |
| Field Health: NDVI curve | Presented as satellite data | Labelled "Sample data" until MODIS data is added |
| History | Three invented seasons with invented yields | Last three seasons from the weather record, with Chakwal district rainfed yields where reported |
| Summer planner and Rewards | Presented as real | Carry a "sample figures" notice |
| Barkat expected yield | 32 maund/acre | 17.9 maund/acre (district rainfed average) |
| First launch | Home showed demo cards until the date was changed | Screens redraw as soon as the engine finishes loading |
| Text-to-speech on Android 11+ | Could fail to find the speech engine | Manifest now declares the needed queries |
| Permissions | Camera and microphone declared but unused | Removed; the camera app and speech recogniser handle their own |
| Leftover travel-app code | 5 screens, 3 repositories, 1 banner image | Deleted (about 4,100 lines) |

## Files

New: `engine/KisanAnswers.kt`, `ui/screens/FieldHealthCards.kt`, `ui/components/SampleDataBanner.kt`, `res/xml/file_paths.xml`.

Changed: `AndroidManifest.xml`, `MainActivity.kt`, `ai/KisanAiService.kt`, `data/repository/KisanRepository.kt`,
`engine/KisanEngine.kt`, `engine/KisanAssets.kt`, `ui/components/BarkatBasisDialog.kt`, `ui/screens/AskScreen.kt`,
`ui/screens/FieldHealthScreen.kt`, `ui/screens/HistoryScreen.kt`, `ui/screens/RewardsScreen.kt`,
`ui/screens/SummerPlanScreen.kt`, `util/AudioHelper.kt`.

Deleted (make sure these are gone after you import, or the build may fail):
`ui/MainScreen.kt`, `ui/components/CommonComponents.kt`, `ui/screens/explore/`, `ui/screens/studio/`,
`ui/screens/journal/`, `ui/screens/transcribe/`, `ui/screens/voice/`, `data/repository/AiRepository.kt`,
`data/repository/PlacesRepository.kt`, `data/repository/JournalRepository.kt`,
`res/drawable/travel_hero_banner_1794184755483.jpg`.

## What you must do in the Firebase console

1. **Enable Gemini.** Open the Firebase project, go to AI Logic, and enable the Gemini Developer API.
   Until this is done the online assistant and the photo check cannot work; the app will say so.
2. **Check App Check.** If enforcement is on for AI Logic, a debug build is blocked. Turn enforcement off
   for the demo or register the debug token.
3. **Google Sign-In.** Add the build's SHA-1 signing fingerprint to the Android app in project settings.
4. **Model name.** `KisanAiService.kt` asks for `gemini-3.8-flash`. If the console lists a different model
   name, change that one string.

## What was tested, and what was not

There is no Android SDK where this was written, so the app itself was not built or run.

- The engine and the offline answers were compiled with Kotlin 2.2.10 and run on a desktop JVM:
  5,180 answers across seven seasons, none containing a dose or a pesticide name.
- The engine still matches the Python backend on 1,297 dates.
- `AskScreen.kt`, `KisanAiService.kt`, `FieldHealthScreen.kt`, `FieldHealthCards.kt`, `HistoryScreen.kt`,
  `SampleDataBanner.kt`, `EngineAdapter.kt` and the changed repository functions were type-checked against
  hand-written stand-ins for the Android, Compose and Firebase classes they use. This catches mistakes in
  this code but not a mismatch with the real libraries.
- NOT tested: the real build, the speech recogniser, the camera and gallery flow, text-to-speech, and
  anything that needs a phone. The edits to `SummerPlanScreen.kt`, `RewardsScreen.kt`, `MainActivity.kt`
  and `AudioHelper.kt` are a few lines each and were reviewed by eye only.

If the build fails, the error message will name a file and line; send it and it can be fixed quickly.

## Still not real

- NDVI curve, summer planner figures, rewards and leaderboard are samples.
- Soil moisture is a model estimate, and every agronomy threshold is a draft.
- History shows district and area records, not the farmer's own diary.
- Speech recognition and the Urdu voice depend on what is installed on the phone.
