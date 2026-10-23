# Qodana

LanBridge uses separate Qodana Cloud projects because Android and Windows require different linters:

| Project | Configuration | Execution |
| --- | --- | --- |
| [LanBridge Android](https://qodana.cloud/projects/20knv) | `android/qodana.yaml` | Android 2026.2 Docker linter on Ubuntu, Java 21 |
| [LanBridge Windows](https://qodana.cloud/projects/j4GyZ) | `windows/qodana.yaml` | Native `qodana-dotnet` on Windows 2022, using the CMake-generated Visual Studio solution |

The GitHub workflows run on pushes to `main` and `releases/*`, and on pull requests from this repository. Fork pull requests do not receive project secrets and are skipped. You can also start either workflow from the repository's **Actions** tab using **Run workflow**.

Open the corresponding Qodana Cloud project to review issues, source locations, and inspection explanations. GitHub run artifacts named `qodana-android-report` and `qodana-windows-report` retain the report and diagnostic logs. A successful workflow confirms the analysis ran; it does not mean the project has no findings.

Both workflows perform full analysis with the recommended profile. They do not apply fixes or commit source changes. No additional zero-issue quality gate or merge protection is imposed during initial adoption. Establish a baseline and agreed failure thresholds after reviewing the first reports.

Project tokens are managed by the Qodana Cloud GitHub App and stored only in GitHub Actions Secrets. YAML contains secret names, never token values. If a project token is rotated, update its matching repository secret through the Qodana/GitHub settings.

The Windows job generates `windows/build/qodana/LanBridge.sln` with Visual Studio 2022 x64 and builds Release before analysis. It writes an ignored `windows/LanBridge.qodana.sln` wrapper with project references pointing back into the build directory. Placing this analysis entry at the source root lets Qodana report paths relative to `windows/`, rather than emit paths such as `../../src/Main.cpp` relative to the build directory. The original generated solution and normal application builds are unchanged. Generated files under `windows/build` and vendored files under `windows/third_party` are excluded from issue analysis. Windows SDK and MSVC are provided by the Windows runner; this job does not attempt to build Win32 code inside a Linux container.

The Android scan uses the existing Gradle wrapper and AGP versions; no app dependency, SDK target, or signing configuration is downgraded for Qodana. Release signing keys are not available to these jobs.

Android analysis intentionally uses a fresh Qodana cache on every run (`use-caches: false`). A restored IDE model can refer to an Android SDK installed in a previous disposable container, skip Gradle sync, and produce unresolved Android/Java types in the next container. Fresh import lets Gradle resolve the SDK and dependencies in the actual scan container. Android cache/results directories are explicitly separate from the Windows job. This trades some scan time for reliable project import; do not restore model caches until SDK provisioning and model reuse have been verified together.

After scanning, `scripts/validate-qodana-report.py` checks the full SARIF execution status and embedded `qodana.sanity.results`. Missing/incomplete reports and sanity problems fail the job even when the linter itself exits successfully. Ordinary application findings do not fail this integrity check and are not suppressed. Reports are still uploaded by the scan action before this check, so a failed analysis can be diagnosed from its artifact. Run the guard's regression tests with `python -m unittest discover -s scripts -p test_validate_qodana_report.py`.

The current Qodana Cloud organization was configured with an Ultimate Plus trial ending 20 November 2026. Continued paid features depend on the organization's active subscription.
