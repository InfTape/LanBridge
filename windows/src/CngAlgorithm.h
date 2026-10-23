#pragma once
#include <windows.h>
#include <bcrypt.h>
#include <memory>

namespace bridge::cng {
struct AlgorithmCloser {
    void operator()(BCRYPT_ALG_HANDLE value) const noexcept {
        BCryptCloseAlgorithmProvider(value,0);
    }
};
// Own the provider before any later initialization step can throw. unique_ptr
// releases it during constructor unwinding as well as ordinary destruction.
using Algorithm = std::unique_ptr<void,AlgorithmCloser>;
}
