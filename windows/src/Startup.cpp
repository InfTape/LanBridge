#include "Startup.h"
#include "ShellTransfer.h"
#include <system_error>

namespace startup {
namespace {
struct RegistryKey {
    HKEY value=nullptr;
    RegistryKey()=default;
    RegistryKey(const RegistryKey&)=delete;
    RegistryKey& operator=(const RegistryKey&)=delete;
    RegistryKey(RegistryKey&&)=delete;
    RegistryKey& operator=(RegistryKey&&)=delete;
    ~RegistryKey() { if(value) RegCloseKey(value); }
};
void Check(LSTATUS result) {
    if(result!=ERROR_SUCCESS) throw std::system_error(result,std::system_category(),"开机启动设置失败");
}
}
std::filesystem::path ExecutablePath() {
    std::wstring path(32768,L'\0');
    const DWORD size=GetModuleFileNameW(nullptr,path.data(),static_cast<DWORD>(path.size()));
    if(!size || size>=path.size()) throw std::runtime_error("无法读取程序路径");
    path.resize(size); return path;
}
std::wstring CommandLine(const std::filesystem::path& executable) {
    const auto path=executable.wstring();
    if(!executable.is_absolute() || path.empty() || path.size()>=32700 || path.find(L'\0')!=std::wstring::npos)
        throw std::invalid_argument("无效的开机启动程序路径");
    return shelltransfer::QuoteArgument(path)+L" --startup";
}
std::optional<std::wstring> ReadCommand(HKEY root,const wchar_t* key) {
    RegistryKey handle; auto result=RegOpenKeyExW(root,key,0,KEY_QUERY_VALUE,&handle.value);
    if(result==ERROR_FILE_NOT_FOUND) return std::nullopt; Check(result);
    DWORD type=0,size=0;
    result=RegQueryValueExW(handle.value,ValueName,nullptr,&type,nullptr,&size);
    if(result==ERROR_FILE_NOT_FOUND) return std::nullopt; Check(result);
    if(type!=REG_SZ || size<sizeof(wchar_t) || size>65536 || size%sizeof(wchar_t)) return std::nullopt;
    std::wstring value(size/sizeof(wchar_t),L'\0');
    Check(RegQueryValueExW(handle.value,ValueName,nullptr,&type,reinterpret_cast<BYTE*>(value.data()),&size));
    if(type!=REG_SZ || size<sizeof(wchar_t) || size%sizeof(wchar_t)) return std::nullopt;
    value.resize(size/sizeof(wchar_t));
    if(value.back()!=L'\0') return std::nullopt;
    value.pop_back(); if(value.find(L'\0')!=std::wstring::npos) return std::nullopt;
    return value;
}
bool IsEnabled(const std::filesystem::path& executable) {
    const auto command=ReadCommand(); const auto expected=CommandLine(executable);
    return command && CompareStringOrdinal(command->c_str(),-1,expected.c_str(),-1,TRUE)==CSTR_EQUAL;
}
void SetEnabled(bool enabled,const std::filesystem::path& executable,HKEY root,const wchar_t* key) {
    RegistryKey handle;
    if(enabled) {
        const auto command=CommandLine(executable);
        Check(RegCreateKeyExW(root,key,0,nullptr,REG_OPTION_NON_VOLATILE,KEY_SET_VALUE,nullptr,&handle.value,nullptr));
        Check(RegSetValueExW(handle.value,ValueName,0,REG_SZ,reinterpret_cast<const BYTE*>(command.c_str()),
            static_cast<DWORD>((command.size()+1)*sizeof(wchar_t))));
    } else {
        auto result=RegOpenKeyExW(root,key,0,KEY_SET_VALUE,&handle.value);
        if(result==ERROR_FILE_NOT_FOUND) return; Check(result);
        result=RegDeleteValueW(handle.value,ValueName);
        if(result!=ERROR_FILE_NOT_FOUND) Check(result);
    }
}
}
