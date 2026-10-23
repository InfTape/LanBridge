#pragma once
#include <windows.h>
#include <objbase.h>
#include <memory>
#include <stdexcept>

namespace win32 {
inline void Check(HRESULT result, const char* message) {
    if(FAILED(result)) throw std::runtime_error(message);
}

// S_FALSE owns one initialization count too; failed construction owns none.
class Apartment {
public:
    explicit Apartment(DWORD model=COINIT_APARTMENTTHREADED) {
        Check(CoInitializeEx(nullptr,model),"Cannot initialize COM");
    }
    ~Apartment() { CoUninitialize(); }
    Apartment(const Apartment&)=delete;
    Apartment& operator=(const Apartment&)=delete;
};

struct LocalFreeDeleter {
    void operator()(void* value) const noexcept { LocalFree(value); }
};
using LocalMemory=std::unique_ptr<void,LocalFreeDeleter>;

struct TaskMemoryDeleter {
    void operator()(void* value) const noexcept { CoTaskMemFree(value); }
};
using TaskString=std::unique_ptr<wchar_t,TaskMemoryDeleter>;
}
