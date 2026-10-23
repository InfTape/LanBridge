#pragma once
#include <windows.h>
#include <bcrypt.h>
#include <memory>

namespace bridge::cng {
// Cleanup failure is diagnostic only; destructors must not replace an active
// exception with another failure.
inline void Cleanup(NTSTATUS status) noexcept {
    if(status<0) OutputDebugStringW(L"LanBridge: CNG resource cleanup failed\n");
}
struct AlgorithmCloser {
    void operator()(BCRYPT_ALG_HANDLE value) const noexcept {
        Cleanup(BCryptCloseAlgorithmProvider(value,0));
    }
};
// Own the provider before any later initialization step can throw. unique_ptr
// releases it during constructor unwinding as well as ordinary destruction.
using Algorithm = std::unique_ptr<void,AlgorithmCloser>;
}
