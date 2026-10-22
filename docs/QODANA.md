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

The Windows job generates `windows/build/qodana/LanBridge.sln` with Visual Studio 2022 x64 and builds Release before analysis. The solution and binaries remain ignored build outputs. Generated files under `windows/build` and vendored files under `windows/third_party` are excluded from issue analysis. Windows SDK and MSVC are provided by the Windows runner; this job does not attempt to build Win32 code inside a Linux container.

The Android scan uses the existing Gradle wrapper and AGP versions; no app dependency, SDK target, or signing configuration is downgraded for Qodana. Release signing keys are not available to these jobs.

The current Qodana Cloud organization was configured with an Ultimate Plus trial ending 20 November 2026. Continued paid features depend on the organization's active subscription.
