#pragma once
#include <windows.h>
#include <filesystem>
#include <optional>
#include <string>

namespace startup {
inline constexpr wchar_t RunKey[] = L"Software\\Microsoft\\Windows\\CurrentVersion\\Run";
inline constexpr wchar_t ValueName[] = L"LanBridge";
std::filesystem::path ExecutablePath();
std::wstring CommandLine(const std::filesystem::path& executable);
std::optional<std::wstring> ReadCommand(HKEY root=HKEY_CURRENT_USER,const wchar_t* key=RunKey);
bool IsEnabled(const std::filesystem::path& executable);
void SetEnabled(bool enabled,const std::filesystem::path& executable,HKEY root=HKEY_CURRENT_USER,const wchar_t* key=RunKey);
}
