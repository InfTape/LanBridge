#include "ContextMenu.h"
#include "CngAlgorithm.h"
#include <windows.h>
#include <appmodel.h>
#include <bcrypt.h>
#include <wincrypt.h>
#include <shlobj.h>
#include <vector>
#include <array>
#include <stdexcept>

namespace contextmenu {
namespace {
void Require(bool value) { if(!value) throw std::runtime_error("Menu registration unavailable"); }
struct Key {
    HKEY value;
    explicit Key(HKEY handle=nullptr):value(handle) {}
    Key(const Key&)=delete;
    Key& operator=(const Key&)=delete;
    Key(Key&&)=delete;
    Key& operator=(Key&&)=delete;
    ~Key(){if(value) RegCloseKey(value);}
};
struct File {
    HANDLE value;
    explicit File(HANDLE handle=INVALID_HANDLE_VALUE):value(handle) {}
    File(const File&)=delete;
    File& operator=(const File&)=delete;
    File(File&&)=delete;
    File& operator=(File&&)=delete;
    ~File(){if(value!=INVALID_HANDLE_VALUE) CloseHandle(value);}
};
struct Hash {
    BCRYPT_ALG_HANDLE algorithm=nullptr; BCRYPT_HASH_HANDLE value=nullptr;
    Hash()=default;
    Hash(const Hash&)=delete;
    Hash& operator=(const Hash&)=delete;
    Hash(Hash&&)=delete;
    Hash& operator=(Hash&&)=delete;
    ~Hash(){if(value) bridge::cng::Cleanup(BCryptDestroyHash(value)); if(algorithm) bridge::cng::Cleanup(BCryptCloseAlgorithmProvider(algorithm,0));}
};
struct Certificate {
    PCCERT_CONTEXT value;
    explicit Certificate(PCCERT_CONTEXT handle=nullptr):value(handle) {}
    Certificate(const Certificate&)=delete;
    Certificate& operator=(const Certificate&)=delete;
    Certificate(Certificate&&)=delete;
    Certificate& operator=(Certificate&&)=delete;
    ~Certificate(){if(value) CertFreeCertificateContext(value);}
};
struct Store {
    HCERTSTORE value;
    explicit Store(HCERTSTORE handle=nullptr):value(handle) {}
    Store(const Store&)=delete;
    Store& operator=(const Store&)=delete;
    Store(Store&&)=delete;
    Store& operator=(Store&&)=delete;
    ~Store(){if(value) CertCloseStore(value,0);}
};
std::wstring Read(HKEY key,const wchar_t* name) {
    DWORD bytes=0; Require(RegGetValueW(key,nullptr,name,RRF_RT_REG_SZ,nullptr,nullptr,&bytes)==ERROR_SUCCESS && bytes>=2 && bytes<=65536 && bytes%2==0);
    std::wstring result(bytes/2,L'\0');
    Require(RegGetValueW(key,nullptr,name,RRF_RT_REG_SZ,nullptr,result.data(),&bytes)==ERROR_SUCCESS);
    result.resize(bytes/2); Require(result.back()==L'\0'); result.pop_back();
    Require(!result.empty() && result.find(L'\0')==std::wstring::npos); return result;
}
std::wstring Hex(const unsigned char* data,size_t size) {
    std::wstring result; result.reserve(size*2);
    for(size_t i=0;i<size;++i) {result+=L"0123456789ABCDEF"[data[i]>>4]; result+=L"0123456789ABCDEF"[data[i]&15];} return result;
}
bool SamePath(const std::filesystem::path& a,const std::filesystem::path& b) {
    if(!a.is_absolute() || !b.is_absolute()) return false;
    const auto left=std::filesystem::weakly_canonical(a).wstring(),right=std::filesystem::weakly_canonical(b).wstring();
    return CompareStringOrdinal(left.c_str(),-1,right.c_str(),-1,TRUE)==CSTR_EQUAL;
}
std::wstring PackageHash(const std::filesystem::path& path) {
    const File file{CreateFileW(path.c_str(),GENERIC_READ,FILE_SHARE_READ,nullptr,OPEN_EXISTING,FILE_FLAG_SEQUENTIAL_SCAN,nullptr)};
    Require(file.value!=INVALID_HANDLE_VALUE); Hash hash;
    Require(BCryptOpenAlgorithmProvider(&hash.algorithm,BCRYPT_SHA256_ALGORITHM,nullptr,0)>=0);
    Require(BCryptCreateHash(hash.algorithm,&hash.value,nullptr,0,nullptr,0,0)>=0);
    std::array<unsigned char,65536> buffer{}; DWORD read=0;
    do {Require(ReadFile(file.value,buffer.data(),static_cast<DWORD>(buffer.size()),&read,nullptr)!=FALSE); if(read) Require(BCryptHashData(hash.value,buffer.data(),read,0)>=0);} while(read);
    std::array<unsigned char,32> digest{}; Require(BCryptFinishHash(hash.value,digest.data(),static_cast<ULONG>(digest.size()),0)>=0); return Hex(digest.data(),digest.size());
}
bool Trusted(const std::filesystem::path& certificatePath,const std::wstring& thumbprint) {
    const File file{CreateFileW(certificatePath.c_str(),GENERIC_READ,FILE_SHARE_READ,nullptr,OPEN_EXISTING,FILE_ATTRIBUTE_NORMAL,nullptr)};
    if(file.value==INVALID_HANDLE_VALUE) return false;
    LARGE_INTEGER size{}; if(!GetFileSizeEx(file.value,&size) || size.QuadPart<=0 || size.QuadPart>65536) return false;
    std::vector<unsigned char> bytes(static_cast<size_t>(size.QuadPart)); DWORD read=0;
    if(!ReadFile(file.value,bytes.data(),static_cast<DWORD>(bytes.size()),&read,nullptr) || read!=bytes.size()) return false;
    const Certificate certificate{CertCreateCertificateContext(X509_ASN_ENCODING,bytes.data(),read)}; if(!certificate.value) return false;
    std::array<unsigned char,20> digest{}; DWORD count=static_cast<DWORD>(digest.size());
    if(!CertGetCertificateContextProperty(certificate.value,CERT_SHA1_HASH_PROP_ID,digest.data(),&count) || count!=digest.size() || Hex(digest.data(),digest.size())!=thumbprint) return false;
    const Store store{CertOpenStore(CERT_STORE_PROV_SYSTEM_W,0,0,CERT_SYSTEM_STORE_LOCAL_MACHINE|CERT_STORE_READONLY_FLAG|CERT_STORE_OPEN_EXISTING_FLAG,L"TrustedPeople")}; if(!store.value) return false;
    const CRYPT_HASH_BLOB hash{count,digest.data()}; const Certificate installed{CertFindCertificateInStore(store.value,X509_ASN_ENCODING,0,CERT_FIND_SHA1_HASH,&hash,nullptr)}; return installed.value!=nullptr;
}
bool RegisteredAt(const std::wstring& fullName,const std::filesystem::path& root) {
    if(!fullName.starts_with(L"LanBridge.ContextMenu_")) return false;
    UINT32 length=0; if(PackageFamilyNameFromFullName(fullName.c_str(),&length,nullptr)!=ERROR_INSUFFICIENT_BUFFER || length>256) return false;
    std::vector<wchar_t> family(length); if(PackageFamilyNameFromFullName(fullName.c_str(),&length,family.data())!=ERROR_SUCCESS) return false;
    UINT32 count=0,characters=0; const auto result=GetPackagesByPackageFamily(family.data(),&count,nullptr,&characters,nullptr);
    if(result!=ERROR_INSUFFICIENT_BUFFER || count!=1 || characters>4096) return false;
    PWSTR name=nullptr; std::vector<wchar_t> names(characters);
    if(GetPackagesByPackageFamily(family.data(),&count,&name,&characters,names.data())!=ERROR_SUCCESS || count!=1 || !name || fullName!=name) return false;
    // The external-path query is exported by KernelBase on supported Windows,
    // but some desktop SDK import libraries omit it. Resolve the system export.
    const auto pathQuery=reinterpret_cast<decltype(&GetPackagePathByFullName2)>(GetProcAddress(GetModuleHandleW(L"kernelbase.dll"),"GetPackagePathByFullName2"));
    if(!pathQuery) return false;
    length=0; if(pathQuery(fullName.c_str(),PackagePathType_EffectiveExternal,&length,nullptr)!=ERROR_INSUFFICIENT_BUFFER || length>32768) return false;
    std::vector<wchar_t> external(length);
    return pathQuery(fullName.c_str(),PackagePathType_EffectiveExternal,&length,external.data())==ERROR_SUCCESS && SamePath(external.data(),root);
}
}
bool IsCurrent(const std::filesystem::path& root,const std::wstring& receiptKey) noexcept {
    try {
        Key key; if(RegOpenKeyExW(HKEY_CURRENT_USER,receiptKey.c_str(),0,KEY_QUERY_VALUE,&key.value)!=ERROR_SUCCESS) return false;
        DWORD version=0,size=sizeof(version);
        if(RegGetValueW(key.value,nullptr,L"Schema",RRF_RT_REG_DWORD,nullptr,&version,&size)!=ERROR_SUCCESS || version!=1) return false;
        if(!SamePath(Read(key.value,L"Location"),root)) return false;
        const auto fullName=Read(key.value,L"PackageFullName");
        if(!RegisteredAt(fullName,root)) return false;
        if(!std::filesystem::is_regular_file(root/L"LanBridge.exe") || !std::filesystem::is_regular_file(root/L"LanBridgeShell.dll")) return false;
        if(PackageHash(root/L"context-menu"/L"LanBridge.ContextMenu.msix")!=Read(key.value,L"PackageHash")) return false;
        return Trusted(root/L"context-menu"/L"LanBridge.ContextMenu.cer",Read(key.value,L"Thumbprint"));
    } catch(...) { return false; }
}
void Refresh() noexcept { SHChangeNotify(SHCNE_ASSOCCHANGED,SHCNF_IDLIST,nullptr,nullptr); }
}
