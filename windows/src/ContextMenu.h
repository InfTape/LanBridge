#pragma once
#include <filesystem>
#include <string>

namespace contextmenu {
inline constexpr auto ReceiptKey=L"Software\\LanBridge\\ContextMenu";
// Query only: no COM activation, package deployment, scripts or child processes.
bool IsCurrent(const std::filesystem::path& root,const std::wstring& receiptKey=ReceiptKey) noexcept;
void Refresh() noexcept;
}
