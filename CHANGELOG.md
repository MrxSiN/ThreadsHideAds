# Changelog

## 1.0.0

- LSPosed scope to `com.instagram.barcelona`.
- Updated the sponsored-content hook from legacy void/no-op behavior to the
  current Boolean `insertItem(Object, Object)` behavior returning `false`.
- Added a unique-match shape fallback, legacy compatibility fallback, and
  diagnostic LSPosed logging.
