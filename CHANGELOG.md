# Changelog

## 1.1.0

- Improved Ad Detection
- Added feed-level ad filtering to remove sponsored items before they are displayed. 
- Added detection for sponsored, promoted, direct-ad, AdChoices, and paid-partnership markers. 
- Added a UI fallback that hides posts containing rendered sponsored labels. 
- Added support for several localized sponsored-label variants. 
- Added automatic restoration for recycled feed views to prevent normal posts from remaining hidden. 
- Improved DexKit scanning by searching dex files available through the Threads class loader. 
- Expanded detection beyond boolean methods to include feed lists, parsers, mappers, and model objects. 
- Improved compatibility with obfuscated and frequently changing Threads builds. 
- Added more detailed LSPosed logging for method discovery, hook installation, and removed ads. 
- Fixed ads still appearing when Threads injected them directly into feed lists. 
- Fixed split-APK and secondary-dex code being missed during method discovery. 
- Fixed potentially incorrect hooks caused by forcing every ad-related boolean method to return false. 
- Fixed the fallback scan being skipped after finding unrelated string matches. 
- Fixed recycled feed cards occasionally hiding legitimate posts.

## 1.0.0

- LSPosed scope to `com.instagram.barcelona`.
- Updated the sponsored-content hook from legacy void/no-op behavior to the
  current Boolean `insertItem(Object, Object)` behavior returning `false`.
- Added a unique-match shape fallback, legacy compatibility fallback, and
  diagnostic LSPosed logging.
