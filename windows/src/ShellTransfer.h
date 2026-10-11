#pragma once
#include <windows.h>
#include <filesystem>
#include <string>
#include <vector>

namespace shelltransfer {
constexpr ULONG_PTR MessageId = 0x4C425346; // LBSF
constexpr size_t MaxPayload = 1024 * 1024;
constexpr size_t MaxFiles = 1024;
using Paths = std::vector<std::filesystem::path>;
std::vector<unsigned char> Encode(const Paths& paths);
Paths Decode(const void* bytes, size_t size);
std::wstring QuoteArgument(const std::wstring& argument);
std::filesystem::path WriteRequest(const Paths& paths);
Paths ReadRequest(const std::filesystem::path& path);
bool Forward(HWND window, const Paths& paths);
void Launch(const std::filesystem::path& executable, const Paths& paths);
}
