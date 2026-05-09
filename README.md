# pdf-edit

An Android PDF editor — built ground-up, with the long-term goal of in-place text editing that preserves the original font (Tier 2 from the staircase below).

**Status:** v0.1.0 — viewer with pinch-zoom, rotation, page indicator, bookmarks drawer.

## Roadmap (the staircase)

Each step ships something usable on its own. We don't move up until the rung below is solid.

| Version | Capability | Status |
|---|---|---|
| **v0.0** | Render PDFs (PdfiumAndroid), open via SAF, scroll | ✅ done |
| **v0.1** | Pinch zoom, page indicator, bookmarks, rotation | ✅ done |
| **v0.2** | Annotations: freehand, highlight, underline, strikethrough, sticky notes, signatures. Save back via PdfBox-Android annotation API. Undo/redo. | ⬜ |
| **v0.3** | Insert new text (place a new text object, choose a font from the user's font folder, write to content stream — not annotation) | ⬜ |
| **v0.4** | Tier 2 — **edit existing text in place** with original-font preservation where possible. Detect missing fonts, prompt the user to provide them. | ⬜ |
| **v1.0** | Vector path editing, image manipulation, multi-touch transforms (Tier 3 territory) | ⬜ |

## Why a new repo

The previous attempt (`LibrePDFEditor`) was an AI-generated stub: editor UI, no editor logic. This repo skips the shell and builds the actual rendering / parsing / editing engine bottom-up.

## Font handling (matters for v0.3 and v0.4)

PDFs embed *subsets* of the fonts they use — only the glyphs originally referenced. To edit a word and add a glyph that wasn't in the subset (e.g. a `ö` in a doc that never used one), the app needs the full font file from somewhere.

**Strategy:** the app does not bundle fonts. When you tap edit on text whose font is missing locally, the app pops a dialog naming the required fonts and waits for you to drop the `.ttf` files into:

```
/storage/emulated/0/Android/data/com.ironshing.pdfedit/files/fonts/
```

The dialog's "Import" button can also pull a font from anywhere (Downloads, OneDrive, etc.) via the system file picker. Once the font is present, retry the edit and it proceeds.

This avoids licensed-font shipping issues and keeps the APK small.

**Recommended sources for common substitutions:**

| You're editing… | Get this (FOSS, metric-compatible) |
|---|---|
| Times New Roman | [Liberation Serif](https://github.com/liberationfonts/liberation-fonts/releases) |
| Arial / Helvetica | [Liberation Sans](https://github.com/liberationfonts/liberation-fonts/releases) |
| Courier New | [Liberation Mono](https://github.com/liberationfonts/liberation-fonts/releases) |
| Anything else | [Google Noto](https://fonts.google.com/noto) — covers most of Unicode |

## Build

Requirements: Android SDK + JDK 21 (`java-21-openjdk-devel` on Fedora — needs `jlink`).

```
./gradlew assembleDebug
```

APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

## Architecture

```
app/src/main/java/com/ironshing/pdfedit/
  MainActivity.kt        Activity host
  AppRoot.kt             Top-level Composable: open / picker / cache copy
  PdfRenderer.kt         Thin PdfiumCore wrapper — open document, render page → bitmap
  PdfViewerScreen.kt     LazyColumn of pages, top app bar, close action
```

Dependencies of note:
- `com.github.barteksc:PdfiumAndroid:pdfium-android-1.8.2` — the native pdfium engine, used directly (no `barteksc/AndroidPdfViewer` wrapper)
- `com.tom-roush:pdfbox-android:2.0.27.0` — pure-Java PDF parser/writer, used for annotations and (eventually) content-stream edits

## License

TBD — likely Apache 2.0. Not yet finalized.
