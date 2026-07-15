# Hook notes

```kotlin
returnType = "void"
strings("SponsoredContentController.processValidatedContent")
```

Replacement:

```kotlin
XC_MethodReplacement.DO_NOTHING
```

## Current fingerprint represented by this module

```text
Access: private
Class name suffix: SponsoredContentController
Method name: insertItem
Prototype shorty: ZLL
```

`ZLL` means a Boolean return and two reference-type parameters.

Replacement:

```java
XC_MethodReplacement.returnConstant(Boolean.FALSE)
```

## Safety behavior

The exact fingerprint is attempted first. If the name changes, the module only
uses the shape fallback when it yields exactly one candidate. It does not hook
an arbitrary method when multiple candidates match.
