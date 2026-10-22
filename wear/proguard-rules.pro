# R8 rules for the release build.
# The app uses no reflection; services and receivers named in the manifest are kept
# automatically, and the libraries bring their own rules.

# Keep line numbers in crash reports readable.
-keepattributes SourceFile,LineNumberTable
