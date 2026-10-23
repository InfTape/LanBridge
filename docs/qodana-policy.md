# Qodana findings: Windows P2/P3 review

Reviewed on 2026-10-23 against the full Windows SARIF for PR #4 (`fd854304`,
825 findings). This is a review policy, not a zero-finding baseline. Android
inspection/import configuration and dependencies are unchanged by this work.

## P2: failure paths and bounds

* Folder picker configuration checks GetOptions, SetOptions and SetTitle.
  Cancellation is silent; other Show/GetResults/GetCount/GetItemAt/path failures
  report an error. Selection is collected completely before anything is sent.
  COM-allocated path buffers are owned during conversion, including exceptions.
* COM initialization is scoped: S_OK and S_FALSE each own one CoUninitialize;
  failure owns none. Command-line storage is scoped too. Failed singleton-lock,
  GUID/path lookup, common-control setup and message retrieval no longer proceed
  as successful initialization. DWM appearance failure only emits diagnostics.
* CheckedSize rejects values outside signed/narrow API length ranges before
  conversion. Folder/filename slices use pointer-plus-length after packet bounds
  validation, avoiding implicit unsigned iterator differences. File lengths use
  signed-safe comparison. DPI conversion has an explicit fallback. The two
  `1024*1024` findings were safe constants, now written with a 64-bit operand.
* Folder recovery keeps failed cleanup receipts for retry, with a diagnostic at
  most once per pass. Discovery reports local failure at most once per service
  run and continues after invalid/oversized datagrams and UDP ICMP errors.
* CNG cleanup failures emit debugger diagnostics and remain non-throwing.
  Discovery's provider is also scoped across allocations and hash failures.

`WindowsFailureIntegration` exercises real COM initialization counts (including
RPC_E_CHANGED_MODE), injected folder-dialog/selection failures, API size limits,
DPI fallback and truncated/overlong folder headers without opening a picker.
`PairingIntegration` verifies that valid live discovery still works after
malformed and oversized UDP packets. CI also runs the existing crypto cleanup,
file/folder transfer and shell-selection regressions.

## P3: source changes

155 local declarations were made const; multi-declarator statements cover
additional reported variables. Handle pointers remain usable for their intended
operations: const qualifies the local value, not the pointed-to resource.
Resource-owning wrappers explicitly prohibit copying/moving. Some were already
implicitly non-copyable; this is a preventive ownership contract, not evidence
of a previously observed double close. Unused state was removed, our declaration
parameter names were aligned, native structures are explicitly zero-initialized
before assigning their size, and event paths have an explicit empty default.
Reserved-name checks use starts_with and address results reserve capacity.

## Optional style rules excluded from the Windows profile

These exact inspection IDs are excluded in `windows/qodana.yaml`. They are
source-style preferences or unmeasured layout suggestions; the recommended
profile remains enabled for other checks. A lower report count caused by these
exclusions must not be described as the same number of fixed product bugs.

| Inspection ID | Reason |
| --- | --- |
| CppInconsistentNaming | Keep the existing PascalCase API/Win32 entry points and local naming; no project-wide renaming migration. |
| CppParameterMayBeConst | Top-level const on a by-value parameter is an implementation preference, not part of the caller's contract. Local-variable const checks remain enabled. |
| CppUseDesignatedInitializers | Both value initialization followed by named field assignment and full aggregate initialization are accepted for native structures. |
| CppTooWideScopeInitStatement; CppTooWideScope | if/switch initializer placement is optional when current lifetimes are correct. |
| CppUseRangeAlgorithm; CppClangTidyModernizeUseRanges; CppUseStructuredBinding | Existing standard algorithms and named object fields remain readable; no mandatory syntax migration. |
| CppClangTidyModernizeUseScopedLock | A lock_guard for one mutex is appropriate; multi-lock ownership still needs individual review. |
| CppClangTidyClangDiagnosticPadded; CppClangTidyPerformanceEnumSize | Native/COM layout and Win32 control IDs do not need reordering/narrowing without measured memory pressure. |
| CppClangTidyReadabilityEnumInitialValue | Separate explicit Win32 control-ID ranges are intentional. |

## Reviewed native idioms retained

The following are not broadly suppressed. Their existing findings may remain
in the report and should be evaluated in context:

* `MIB_IF_ROW2 row{}` is an output buffer for GetIfEntry2. Only InterfaceLuid is
  an input; other fields are read only when that call succeeds. Zero values of
  its output enum fields are not used as initialized application enum state.
* GetProcAddress casts use the exact Windows function signature. They are needed
  for runtime availability/fallback, rather than calls through incompatible
  signatures. Win32 control IDs/message parameters/MAKEINTRESOURCE also involve
  intentional integer/handle conversions.
* RegisterWindowMessageW does not throw C++ exceptions. Its global initialization
  is not the throwing-static-initialization defect suggested by a generic rule.
  DllMain/DllGetClassObject/wWinMain retain their required external signatures.
* Hash Add/Finish mutate the underlying CNG hash state even if the wrapper's
  pointer does not change; making those methods const would misstate their role.
* Explicit standard includes and size casts can be useful even where Windows
  headers or the current x64 typedefs happen to make them redundant.

No ignored-return, bounds, lifetime, empty-catch, analyzer-integrity or dependency
resolution inspection is disabled. No SARIF baseline is created to hide issues.
Remaining findings are review inputs, not a claim that all of them are defects.

References:

* [COM initialization and matching cleanup](https://learn.microsoft.com/en-us/windows/win32/api/combaseapi/nf-combaseapi-coinitializeex)
* [Common file dialog options](https://learn.microsoft.com/en-us/windows/win32/shell/common-file-dialog)
* [Qodana YAML inspection configuration](https://www.jetbrains.com/help/qodana/qodana-yaml.html)
