# R8 for the release build. The code is public: shrink, but keep class and member names, so crash
# traces stay readable and Throwable.userText's class-name fallback shows real names.
-dontobfuscate

# Keep rules go here only when an R8 warning or a release smoke test proves them necessary.
