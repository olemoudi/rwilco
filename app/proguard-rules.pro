# R8 shrinks the release (0.158.0); it does not rename. Rwilco is a personal, open-source app
# with nothing to hide, and every name kept costs about a megabyte and buys two things worth more:
# the diagnostics say class names out loud (Diag.note with `::class.simpleName`, DiagReport,
# Updater), and a crash's stack trace reads as it is, with no mapping file to keep per release.
-dontobfuscate
